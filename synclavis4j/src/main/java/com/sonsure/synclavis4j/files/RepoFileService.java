package com.sonsure.synclavis4j.files;

import com.sonsure.synclavis4j.files.dto.RepoFileContentDto;
import com.sonsure.synclavis4j.files.dto.RepoFileEntryDto;
import com.sonsure.synclavis4j.files.dto.RepoFileListDto;
import com.sonsure.synclavis4j.files.dto.RepoFileSearchDto;
import com.sonsure.synclavis4j.repo.RepoRepositoryManager;
import com.sonsure.synclavis4j.repo.RepoService;
import com.sonsure.synclavis4j.shared.exception.ErrorCode;
import com.sonsure.synclavis4j.shared.exception.GitOperationException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.ignore.IgnoreNode;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.FS;
import org.springframework.stereotype.Service;

/**
 * 仓库内文件浏览（只读，见《详细设计文档》§6.2「仓库文件浏览（只读）」）。
 *
 * <p>与 {@code fs.FsBrowseService} 的区别：这里是<b>仓库内相对路径</b>，并且会按
 * {@code .gitignore} / {@code .git/info/exclude} / {@code core.excludesFile} 过滤，
 * 语义与 {@code git status} 同源。</p>
 *
 * <p>所有路径在落地到 {@link File} 之前都经过规范化与工作区越界校验，
 * 挡住绝对路径、{@code ..} 与符号链接逃逸。</p>
 */
@Service
public class RepoFileService {

    /** 单目录返回的条目上限，避免超大目录（如 node_modules）拖垮界面 */
    private static final int MAX_ENTRIES = 2000;

    /** 搜索结果上限：命中这么多就停止收集 */
    private static final int MAX_SEARCH_MATCHES = 200;

    /** 搜索遍历上限：超大仓库里「关键字不存在」会走遍全树，必须有界 */
    private static final int MAX_SEARCH_VISITED = 200000;

    /** 文件内容上限：超过则不读入内存 */
    private static final long MAX_CONTENT_BYTES = 1024L * 1024L;

    /** 二进制嗅探的采样字节数（与 git 的启发式一致：前 8000 字节出现 NUL 即视为二进制） */
    private static final int BINARY_SNIFF_BYTES = 8000;

    private static final String ENCODING_UTF8 = "UTF-8";
    private static final String ENCODING_GBK = "GBK";
    private static final String ENCODING_LATIN1 = "ISO-8859-1";

    private final RepoRepositoryManager repositories;
    private final RepoService repoService;

    public RepoFileService(RepoRepositoryManager repositories, RepoService repoService) {
        this.repositories = repositories;
        this.repoService = repoService;
    }

    /** 列出仓库内某个目录的直接子项。 */
    public RepoFileListDto list(String repoId, String rawPath, boolean showIgnored) {
        Repository repository = acquire(repoId);
        File workTree = workTree(repository);
        String relative = normalizeRelative(rawPath);
        File dir = resolveInside(workTree, relative);
        if (!dir.exists()) {
            throw new GitOperationException(ErrorCode.INVALID_PATH, "路径不存在：" + relative);
        }
        if (!dir.isDirectory()) {
            throw new GitOperationException(ErrorCode.INVALID_PATH, "不是目录：" + relative);
        }
        File[] children = dir.listFiles();
        if (children == null) {
            throw new GitOperationException(ErrorCode.PERMISSION_DENIED, "没有权限读取目录：" + relative);
        }

        IgnoreResolver ignore = new IgnoreResolver(repository, workTree);
        List<RepoFileEntryDto> dirs = new ArrayList<RepoFileEntryDto>();
        List<RepoFileEntryDto> files = new ArrayList<RepoFileEntryDto>();
        boolean truncated = false;

        for (File child : children) {
            String name = child.getName();
            // .git 目录任何情况下都不暴露
            if (Constants.DOT_GIT.equals(name)) {
                continue;
            }
            String childPath = relative.isEmpty() ? name : relative + "/" + name;
            boolean symlink = Files.isSymbolicLink(child.toPath());
            // 符号链接不跟随：一律按文件处理，避免指到仓库外
            boolean isDir = !symlink && child.isDirectory();
            boolean ignored = ignore.isIgnored(childPath, isDir);
            if (ignored && !showIgnored) {
                continue;
            }
            long size = isDir ? 0L : child.length();
            RepoFileEntryDto entry = new RepoFileEntryDto(name, childPath, isDir, size, ignored, symlink);
            if (isDir) {
                dirs.add(entry);
            } else {
                files.add(entry);
            }
        }

        sortByName(dirs);
        sortByName(files);

        List<RepoFileEntryDto> entries = new ArrayList<RepoFileEntryDto>();
        for (RepoFileEntryDto entry : dirs) {
            if (entries.size() >= MAX_ENTRIES) {
                truncated = true;
                break;
            }
            entries.add(entry);
        }
        if (!truncated) {
            for (RepoFileEntryDto entry : files) {
                if (entries.size() >= MAX_ENTRIES) {
                    truncated = true;
                    break;
                }
                entries.add(entry);
            }
        }
        return new RepoFileListDto(relative, entries, truncated);
    }

    /**
     * 按关键字搜索仓库内文件：大小写不敏感，匹配**完整相对路径**。
     *
     * <p>树是懒加载的（只加载已展开的目录），所以「文件名过滤」不能只在前端做 ——
     * 否则用户输入一个位于未展开目录里的文件名会得到「无匹配」。这里做一次有界的
     * 全仓库遍历，忽略规则与 {@link #list} 完全一致。</p>
     *
     * <p>只返回文件：目录只是通往文件的中间层，搜索结果里混入目录反而要处理「跳转后
     * 再展开」的额外状态。</p>
     */
    public RepoFileSearchDto search(String repoId, String rawQuery, boolean showIgnored) {
        Repository repository = acquire(repoId);
        File workTree = workTree(repository);
        String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase();
        SearchCollector collector = new SearchCollector();
        if (!query.isEmpty()) {
            IgnoreResolver ignore = new IgnoreResolver(repository, workTree);
            collectMatches(workTree, "", query, showIgnored, ignore, collector);
            sortByPath(collector.matches);
        }
        return new RepoFileSearchDto(query, collector.matches, collector.truncated);
    }

    /** 深度优先遍历；命中数或遍历量触顶即停。 */
    private void collectMatches(File dir, String relative, String query, boolean showIgnored,
                                IgnoreResolver ignore, SearchCollector collector) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (collector.matches.size() >= MAX_SEARCH_MATCHES
                    || collector.visited >= MAX_SEARCH_VISITED) {
                collector.truncated = true;
                return;
            }
            String name = child.getName();
            if (Constants.DOT_GIT.equals(name)) {
                continue;
            }
            collector.visited++;
            String path = relative.isEmpty() ? name : relative + "/" + name;
            boolean symlink = Files.isSymbolicLink(child.toPath());
            boolean isDir = !symlink && child.isDirectory();
            boolean ignored = ignore.isIgnored(path, isDir);
            if (ignored && !showIgnored) {
                continue;
            }
            if (isDir) {
                collectMatches(child, path, query, showIgnored, ignore, collector);
            } else if (path.toLowerCase().contains(query)) {
                collector.matches.add(
                        new RepoFileEntryDto(name, path, false, child.length(), ignored, symlink));
            }
        }
    }

    /** 读取仓库内单个文件的内容（只读）。 */
    public RepoFileContentDto content(String repoId, String rawPath) {
        Repository repository = acquire(repoId);
        File workTree = workTree(repository);
        String relative = normalizeRelative(rawPath);
        if (relative.isEmpty()) {
            throw new GitOperationException(ErrorCode.INVALID_PATH, "文件路径不能为空");
        }
        File file = resolveInside(workTree, relative);
        if (!file.exists()) {
            throw new GitOperationException(ErrorCode.INVALID_PATH, "文件不存在：" + relative);
        }
        if (file.isDirectory()) {
            throw new GitOperationException(ErrorCode.INVALID_PATH, "不是文件：" + relative);
        }

        long size = file.length();
        if (size > MAX_CONTENT_BYTES) {
            return new RepoFileContentDto(relative, size, false, true, null, null);
        }

        byte[] bytes = readAllBytes(file);
        if (isBinary(bytes)) {
            return new RepoFileContentDto(relative, size, true, false, null, null);
        }
        Decoded decoded = decode(bytes);
        return new RepoFileContentDto(relative, size, false, false, decoded.encoding, decoded.text);
    }

    private Repository acquire(String repoId) {
        return repositories.acquire(repoId, repoService.pathOf(repoId));
    }

    private File workTree(Repository repository) {
        try {
            return repository.getWorkTree();
        } catch (RuntimeException e) {
            // 裸仓库没有工作区
            throw new GitOperationException(ErrorCode.INVALID_PATH, "裸仓库没有工作区，无法浏览文件", null, e);
        }
    }

    /**
     * 校验并规范化仓库内相对路径。
     *
     * <p>只接受相对路径；绝对路径、{@code ..}、{@code .git} 段一律拒绝。
     * 反斜杠统一成 {@code /}，去掉首尾与重复的分隔符。</p>
     */
    private String normalizeRelative(String rawPath) {
        String value = rawPath == null ? "" : rawPath.trim();
        if (value.isEmpty()) {
            return "";
        }
        value = value.replace('\\', '/');
        if (value.startsWith("/") || value.contains(":") || value.contains("\u0000")) {
            throw new GitOperationException(ErrorCode.INVALID_PATH, "只接受仓库内相对路径：" + rawPath);
        }
        List<String> segments = new ArrayList<String>();
        for (String segment : value.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new GitOperationException(ErrorCode.INVALID_PATH, "路径不能越出仓库：" + rawPath);
            }
            if (Constants.DOT_GIT.equals(segment)) {
                throw new GitOperationException(ErrorCode.INVALID_PATH, "不能浏览 .git 目录：" + rawPath);
            }
            segments.add(segment);
        }
        StringBuilder builder = new StringBuilder();
        for (String segment : segments) {
            if (builder.length() > 0) {
                builder.append('/');
            }
            builder.append(segment);
        }
        return builder.toString();
    }

    /**
     * 把相对路径解析成工作区内的真实文件，并校验规范化后仍在工作区内。
     *
     * <p>规范化会解析符号链接，因此链接指向仓库外时会被这里挡住。</p>
     */
    private File resolveInside(File workTree, String relative) {
        File candidate = relative.isEmpty() ? workTree : new File(workTree, relative);
        try {
            String rootPath = workTree.getCanonicalPath();
            String targetPath = candidate.getCanonicalPath();
            if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
                throw new GitOperationException(ErrorCode.INVALID_PATH, "路径不能越出仓库：" + relative);
            }
            return new File(targetPath);
        } catch (IOException e) {
            throw new GitOperationException(ErrorCode.INTERNAL, "解析路径失败：" + e.getMessage(), null, e);
        }
    }

    private byte[] readAllBytes(File file) {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] buffer = new byte[(int) Math.min(MAX_CONTENT_BYTES, Math.max(0L, file.length()))];
            int offset = 0;
            while (offset < buffer.length) {
                int read = in.read(buffer, offset, buffer.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            if (offset == buffer.length) {
                return buffer;
            }
            byte[] actual = new byte[offset];
            System.arraycopy(buffer, 0, actual, 0, offset);
            return actual;
        } catch (IOException e) {
            throw new GitOperationException(ErrorCode.PERMISSION_DENIED, "读取文件失败：" + file.getName(), null, e);
        } finally {
            closeQuietly(in);
        }
    }

    /** git 的二进制启发式：采样区出现 NUL 即视为二进制。 */
    private boolean isBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, BINARY_SNIFF_BYTES);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 依次尝试 UTF-8 → GBK → ISO-8859-1，返回首个可完整解码的结果。
     *
     * <p>ISO-8859-1 能解码任意字节序列，所以兜底一定能成功。</p>
     */
    private Decoded decode(byte[] bytes) {
        byte[] body = stripBom(bytes);
        String[] candidates = {ENCODING_UTF8, ENCODING_GBK, ENCODING_LATIN1};
        for (String name : candidates) {
            try {
                Charset charset = Charset.forName(name);
                CharsetDecoder decoder = charset.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
                return new Decoded(name, decoder.decode(ByteBuffer.wrap(body)).toString());
            } catch (CharacterCodingException e) {
                // 换下一个候选编码
            } catch (RuntimeException e) {
                // 该 JVM 不支持此编码，继续尝试
            }
        }
        return new Decoded(ENCODING_LATIN1, new String(body, StandardCharsets.ISO_8859_1));
    }

    /** 去掉 UTF-8 BOM，避免首行出现不可见字符。 */
    private byte[] stripBom(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            byte[] body = new byte[bytes.length - 3];
            System.arraycopy(bytes, 3, body, 0, body.length);
            return body;
        }
        return bytes;
    }

    private void sortByName(List<RepoFileEntryDto> entries) {
        Collections.sort(entries, new Comparator<RepoFileEntryDto>() {
            @Override
            public int compare(RepoFileEntryDto left, RepoFileEntryDto right) {
                return left.getName().compareToIgnoreCase(right.getName());
            }
        });
    }

    private void sortByPath(List<RepoFileEntryDto> entries) {
        Collections.sort(entries, new Comparator<RepoFileEntryDto>() {
            @Override
            public int compare(RepoFileEntryDto left, RepoFileEntryDto right) {
                return left.getPath().compareToIgnoreCase(right.getPath());
            }
        });
    }

    private void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException e) {
                // 关闭失败不影响已读取的内容
            }
        }
    }

    private static final class Decoded {
        private final String encoding;
        private final String text;

        private Decoded(String encoding, String text) {
            this.encoding = encoding;
            this.text = text;
        }
    }

    /** 搜索过程中的可变累加器（JDK 8 下没有更轻的写法）。 */
    private static final class SearchCollector {
        private final List<RepoFileEntryDto> matches = new ArrayList<RepoFileEntryDto>();
        private int visited;
        private boolean truncated;
    }

    /**
     * 忽略规则解析：逐层加载 {@code .gitignore}，深度优先于浅层，规则内后者覆盖前者。
     *
     * <p>一次请求内复用（节点缓存），不跨请求持有，避免文件改动后读到过期规则。</p>
     */
    private static final class IgnoreResolver {

        private final Repository repository;
        private final File workTree;
        private final Map<String, IgnoreNode> cache = new HashMap<String, IgnoreNode>();

        private IgnoreResolver(Repository repository, File workTree) {
            this.repository = repository;
            this.workTree = workTree;
        }

        /**
         * 判断仓库内相对路径是否被忽略。
         *
         * <p>任一祖先目录被忽略，其后代整体被忽略（与 git 一致）。</p>
         */
        private boolean isIgnored(String relative, boolean isDir) {
            String[] segments = relative.split("/");
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < segments.length - 1; i++) {
                if (i > 0) {
                    prefix.append('/');
                }
                prefix.append(segments[i]);
                if (matchesAnyLevel(prefix.toString(), true)) {
                    return true;
                }
            }
            return matchesAnyLevel(relative, isDir);
        }

        /** 从最深的 .gitignore 向上逐级比对，先命中者生效。 */
        private boolean matchesAnyLevel(String relative, boolean isDir) {
            String[] segments = relative.split("/");
            for (int depth = segments.length - 1; depth >= 0; depth--) {
                String base = joinSegments(segments, depth);
                IgnoreNode node = nodeOf(base);
                if (node == null) {
                    continue;
                }
                String scoped = base.isEmpty() ? relative : relative.substring(base.length() + 1);
                Boolean result = node.checkIgnored(scoped, isDir);
                if (result != null) {
                    return result.booleanValue();
                }
            }
            return false;
        }

        private String joinSegments(String[] segments, int count) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < count; i++) {
                if (builder.length() > 0) {
                    builder.append('/');
                }
                builder.append(segments[i]);
            }
            return builder.toString();
        }

        private IgnoreNode nodeOf(String relativeDir) {
            if (cache.containsKey(relativeDir)) {
                return cache.get(relativeDir);
            }
            File dir = relativeDir.isEmpty() ? workTree : new File(workTree, relativeDir);
            IgnoreNode node = loadNode(dir, relativeDir.isEmpty());
            cache.put(relativeDir, node);
            return node;
        }

        private IgnoreNode loadNode(File dir, boolean root) {
            IgnoreNode node = new IgnoreNode();
            if (root) {
                // 与 git 一致：core.excludesFile → .git/info/exclude → 根 .gitignore
                FS fs = repository.getFS();
                Path excludes = repository.getConfig().getPath(
                        ConfigConstants.CONFIG_CORE_SECTION,
                        null,
                        ConfigConstants.CONFIG_KEY_EXCLUDESFILE,
                        fs,
                        null,
                        null);
                if (excludes != null) {
                    parse(node, excludes.toFile());
                }
                parse(node, fs.resolve(repository.getDirectory(), Constants.INFO_EXCLUDE));
            }
            parse(node, new File(dir, Constants.DOT_GIT_IGNORE));
            return node.getRules().isEmpty() ? null : node;
        }

        private void parse(IgnoreNode node, File file) {
            if (file == null || !file.isFile() || !file.canRead()) {
                return;
            }
            InputStream in = null;
            try {
                in = new FileInputStream(file);
                node.parse(file.getAbsolutePath(), in);
            } catch (IOException e) {
                // 忽略规则读不到时按「未忽略」处理，不阻断浏览
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        // 忽略
                    }
                }
            }
        }
    }
}

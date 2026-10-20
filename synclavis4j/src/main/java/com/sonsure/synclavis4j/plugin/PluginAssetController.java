package com.sonsure.synclavis4j.plugin;

import java.nio.file.Path;
import javax.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

/**
 * 插件静态资源（见《详细设计文档》§6.4.3）。
 *
 * <p>该端点直接返回文件内容，不使用统一响应信封；只允许读取插件目录内的普通文件，
 * 任何越界、目录、不可读文件统一 404。</p>
 */
@RestController
public class PluginAssetController {

    private final PluginService pluginService;

    public PluginAssetController(PluginService pluginService) {
        this.pluginService = pluginService;
    }

    @GetMapping("/plugin-assets/{id}/**")
    public ResponseEntity<Resource> get(@PathVariable("id") String id, HttpServletRequest request) {
        String uri = request.getRequestURI();
        String prefix = request.getContextPath() + "/plugin-assets/" + id + "/";
        if (!uri.startsWith(prefix)) {
            return ResponseEntity.notFound().build();
        }
        String relative;
        try {
            relative = UriUtils.decode(uri.substring(prefix.length()), "UTF-8");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }

        Path file = pluginService.resolveAsset(id, relative);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        MediaType mediaType = MediaTypeFactory.getMediaType(file.getFileName().toString())
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.noCache())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(file));
    }
}

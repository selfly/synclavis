package com.sonsure.synclavis4j.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * harness 发现与选择（见《详细设计文档》§6.5.1）。
 *
 * <p>Spring 会把所有 {@link HarnessProvider} bean 注入进来；没有 provider 时保持空集合，
 * 核心返回 {@code HARNESS_UNAVAILABLE}，而不是启动失败。</p>
 */
@Component
public class HarnessRegistry {

    private static final Logger log = LoggerFactory.getLogger(HarnessRegistry.class);

    private final Map<String, HarnessProvider> providers;

    public HarnessRegistry(List<HarnessProvider> providers) {
        Map<String, HarnessProvider> discovered = new TreeMap<String, HarnessProvider>();
        if (providers != null) {
            for (HarnessProvider provider : providers) {
                HarnessDescriptor descriptor;
                try {
                    descriptor = provider.descriptor();
                } catch (RuntimeException e) {
                    log.warn("忽略无法描述的 AI harness: {}", provider.getClass().getName(), e);
                    continue;
                }
                if (descriptor == null || descriptor.getId() == null
                        || descriptor.getId().trim().isEmpty()) {
                    log.warn("忽略缺少 id 的 AI harness: {}", provider.getClass().getName());
                    continue;
                }
                String id = descriptor.getId().trim();
                if (discovered.put(id, provider) != null) {
                    throw new IllegalStateException("重复的 AI harness id: " + id);
                }
            }
        }
        this.providers = Collections.unmodifiableMap(discovered);
    }

    /** 当前所有 harness；调用 provider 以获取最新的 available 状态。 */
    public List<HarnessDescriptor> descriptors() {
        List<HarnessDescriptor> result = new ArrayList<HarnessDescriptor>();
        for (HarnessProvider provider : providers.values()) {
            try {
                HarnessDescriptor descriptor = provider.descriptor();
                if (descriptor != null && descriptor.getId() != null) {
                    result.add(descriptor);
                }
            } catch (RuntimeException e) {
                log.warn("读取 AI harness 描述失败: {}", provider.getClass().getName(), e);
            }
        }
        return result;
    }

    public HarnessProvider provider(String id) {
        return id == null ? null : providers.get(id);
    }

    public HarnessDescriptor descriptor(String id) {
        HarnessProvider provider = provider(id);
        if (provider == null) {
            return null;
        }
        try {
            return provider.descriptor();
        } catch (RuntimeException e) {
            log.warn("读取 AI harness 描述失败: {}", id, e);
            return null;
        }
    }

    /** 按 id 排序取第一个可用 harness，保证没有显式选择时行为可预测。 */
    public HarnessProvider firstAvailable() {
        for (HarnessProvider provider : providers.values()) {
            try {
                HarnessDescriptor descriptor = provider.descriptor();
                if (descriptor != null && descriptor.isAvailable()) {
                    return provider;
                }
            } catch (RuntimeException e) {
                log.warn("读取 AI harness 描述失败: {}", provider.getClass().getName(), e);
            }
        }
        return null;
    }
}

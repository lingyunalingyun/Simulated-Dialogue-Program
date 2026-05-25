package org.example.personachat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 一侧（左/右）的表情包/图片库。
 * 目录结构（位于 timelineDir）：
 *   personas/&lt;side&gt;/images/             图片文件
 *   personas/&lt;side&gt;/images_meta.json     索引 + 标签
 */
public class ImageLibrary {
    private static final Set<String> EXTS = Set.of(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp");

    private final String side;          // "left" / "right"
    private File timelineDir;           // 由 App 在切换时间线时 reload
    private final Map<String, List<String>> tags = new LinkedHashMap<>();   // 文件名 → tag 列表
    private final Random rnd = new Random();

    public ImageLibrary(String side) { this.side = side; }

    public String side() { return side; }
    public File imagesDir() { return new File(timelineDir, "personas/" + side + "/images"); }
    public File metaFile()  { return new File(timelineDir, "personas/" + side + "/images_meta.json"); }
    public String relativePath(String fileName) { return "personas/" + side + "/images/" + fileName; }
    public File file(String fileName) { return new File(imagesDir(), fileName); }

    public boolean isEmpty() { return tags.isEmpty(); }
    public int size() { return tags.size(); }

    /** 切换时间线/启动时调用：重新指向新 timelineDir，从 meta + 目录扫描重建索引。 */
    public void load(File timelineDir) {
        this.timelineDir = timelineDir;
        tags.clear();
        File dir = imagesDir();
        if (!dir.isDirectory()) return;

        // 1. 从 meta 读 tags
        Map<String, List<String>> savedTags = new HashMap<>();
        File mf = metaFile();
        if (mf.exists()) {
            try {
                JSONObject root = new JSONObject(Files.readString(mf.toPath()));
                JSONArray arr = root.optJSONArray("files");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        String n = o.optString("name", "");
                        if (n.isEmpty()) continue;
                        List<String> ts = new ArrayList<>();
                        JSONArray ta = o.optJSONArray("tags");
                        if (ta != null) for (int j = 0; j < ta.length(); j++) ts.add(ta.getString(j));
                        savedTags.put(n, ts);
                    }
                }
            } catch (Exception ignored) {}
        }

        // 2. 扫目录，只收录真实存在且扩展名合法的；meta 里有则带上 tags
        File[] files = dir.listFiles();
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File f : files) {
                if (!f.isFile()) continue;
                String n = f.getName();
                int dot = n.lastIndexOf('.');
                if (dot < 0) continue;
                if (!EXTS.contains(n.substring(dot).toLowerCase())) continue;
                tags.put(n, new ArrayList<>(savedTags.getOrDefault(n, List.of())));
            }
        }
    }

    public void save() {
        if (timelineDir == null) return;
        try {
            File mf = metaFile();
            mf.getParentFile().mkdirs();
            JSONArray arr = new JSONArray();
            for (var e : tags.entrySet()) {
                JSONArray ta = new JSONArray();
                for (String t : e.getValue()) ta.put(t);
                arr.put(new JSONObject().put("name", e.getKey()).put("tags", ta));
            }
            JSONObject root = new JSONObject().put("files", arr);
            Files.writeString(mf.toPath(), root.toString(2));
        } catch (Exception ignored) {}
    }

    /** 把一个文件夹里所有合法图片复制进库。返回新增/覆盖的数量。 */
    public int importFolder(File srcDir) {
        if (srcDir == null || !srcDir.isDirectory()) return 0;
        File dst = imagesDir();
        dst.mkdirs();
        int n = 0;
        File[] files = srcDir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            if (dot < 0) continue;
            if (!EXTS.contains(name.substring(dot).toLowerCase())) continue;
            try {
                Path target = new File(dst, name).toPath();
                Files.copy(f.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
                if (!tags.containsKey(name)) tags.put(name, new ArrayList<>());
                n++;
            } catch (Exception ignored) {}
        }
        save();
        return n;
    }

    /** 单张导入（用户从 FileChooser 选一张图发出去时用）。返回库内文件名。 */
    public String importOne(File src) {
        if (src == null || !src.isFile()) return null;
        String name = src.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return null;
        if (!EXTS.contains(name.substring(dot).toLowerCase())) return null;
        File dst = imagesDir();
        dst.mkdirs();
        // 重名则加序号
        File out = new File(dst, name);
        int k = 1;
        while (out.exists()) {
            String base = name.substring(0, dot), ext = name.substring(dot);
            out = new File(dst, base + "_" + k + ext);
            k++;
        }
        try {
            Files.copy(src.toPath(), out.toPath());
            tags.put(out.getName(), new ArrayList<>());
            save();
            return out.getName();
        } catch (Exception e) {
            return null;
        }
    }

    /** 按 tag 选一张。tag 为空/"?"/无匹配 → 随机。空库 → null。 */
    public String selectByTag(String tag) {
        if (tags.isEmpty()) return null;
        List<String> candidates;
        if (tag == null || tag.isBlank() || tag.equals("?") || tag.equals("？")) {
            candidates = new ArrayList<>(tags.keySet());
        } else {
            String norm = tag.strip();
            candidates = tags.entrySet().stream()
                    .filter(e -> e.getValue().stream().anyMatch(t -> t.equalsIgnoreCase(norm) || t.contains(norm) || norm.contains(t)))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            if (candidates.isEmpty()) candidates = new ArrayList<>(tags.keySet());
        }
        return candidates.get(rnd.nextInt(candidates.size()));
    }

    public List<String> tagsOf(String fileName) {
        return new ArrayList<>(tags.getOrDefault(fileName, List.of()));
    }

    public void setTags(String fileName, List<String> newTags) {
        if (!tags.containsKey(fileName)) return;
        List<String> clean = new ArrayList<>();
        for (String t : newTags) {
            if (t == null) continue;
            String s = t.strip();
            if (s.isEmpty()) continue;
            if (!clean.contains(s)) clean.add(s);
        }
        tags.put(fileName, clean);
        save();
    }

    /** 所有图的 tag 并集（去重排序），用于注入到 prompt 让 AI 知道有哪些标签可选。 */
    public List<String> tagPool() {
        Set<String> all = new LinkedHashSet<>();
        for (List<String> ts : tags.values()) all.addAll(ts);
        List<String> out = new ArrayList<>(all);
        Collections.sort(out);
        return out;
    }

    public List<String> fileNames() { return new ArrayList<>(tags.keySet()); }

    /** 清空整个库：删除 images/ 目录所有文件 + meta。返回删除的张数。 */
    public int clearAll() {
        if (timelineDir == null) return 0;
        int n = 0;
        File dir = imagesDir();
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.delete()) n++;
                }
            }
        }
        tags.clear();
        File mf = metaFile();
        if (mf.exists()) mf.delete();
        return n;
    }
}

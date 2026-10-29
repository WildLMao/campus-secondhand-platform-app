package cn.campus;

import static cn.campus.Store.p;
import static cn.campus.MarketService.*;
import java.util.*;
import java.nio.file.*;
import java.io.*;
import javax.imageio.ImageIO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/assets")
public class AssetController {
    final Store db;
    final MarketService service;
    final ApiController api;
    final Path root;

    AssetController(Store db, MarketService service, ApiController api, @Value("${app.upload-dir}") String dir)
            throws IOException {
        this.db = db;
        this.service = service;
        this.api = api;
        this.root = Path.of(dir).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    @PostMapping
    Object upload(@RequestParam MultipartFile file, @RequestParam String kind, HttpServletRequest req)
            throws IOException {
        String uid = api.uid(req);
        require(Set.of("ITEM", "VERIFY").contains(kind), "图片用途无效。");
        if (kind.equals("ITEM"))
            service.eligible(uid);
        else
            require(n(service.account(uid), "restricted") == 0, "受限账户不能提交认证材料。");
        require(!file.isEmpty() && file.getSize() <= 5 * 1024 * 1024, "图片不能空，且最大为 5MB。");
        byte[] bytes = file.getBytes();
        String type;
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            require(readers.hasNext(), "只支持 JPEG/PNG 图片。");
            var reader = readers.next();
            try {
                reader.setInput(input);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                require(Set.of("jpeg", "jpg", "png").contains(format), "只支持 JPEG/PNG 图片。");
                require((long) reader.getWidth(0) * reader.getHeight(0) <= 16000000, "图片像素过大，请缩小后上传。");
                type = format.equals("png") ? "image/png" : "image/jpeg";
            } finally {
                reader.dispose();
            }
        }
        String id = id();
        Path dest = root.resolve(id);
        Files.write(dest, bytes, StandardOpenOption.CREATE_NEW);
        try {
            db.exec("insert into assets(id,owner_id,kind,media_type,created_at) values(#{p.id},#{p.u},#{p.k},#{p.m},#{p.t})",
                    p("id", id, "u", uid, "k", kind, "m", type, "t", now()));
        } catch (Exception e) {
            Files.deleteIfExists(dest);
            throw e;
        }
        return p("id", id);
    }

    @GetMapping("/{id}")
    ResponseEntity<byte[]> get(@PathVariable String id, HttpServletRequest req) throws IOException {
        require(id.matches("[a-f0-9-]{36}"), "图片标识无效。");
        var a = db.one("select * from assets where id=#{p.id}", p("id", id));
        if (a == null)
            throw new ApiError(404, "图片不存在。");
        boolean visible = false;
        if (s(a, "kind").equals("ITEM"))
            visible = db.one(
                    "select i.id from items i join accounts a on a.id=i.seller_id where i.image_id=#{p.id} and i.status='在售' and i.hidden=0 and a.restricted=0 and not exists(select 1 from reservations r where r.item_id=i.id)",
                    p("id", id)) != null;
        if (!visible) {
            String uid = api.uid(req);
            visible = uid.equals(s(a, "owner_id"));
            if (!visible && s(service.account(uid), "role").equals("ADMIN")) {
                service.audit(uid, "查看审核图片", id, "审核治理");
                visible = true;
            }
            if (!visible && s(a, "kind").equals("ITEM"))
                visible = db.one(
                        "select id from orders where image_id=#{p.id} and (buyer_id=#{p.u} or seller_id=#{p.u})",
                        p("id", id, "u", uid)) != null;
        }
        if (!visible)
            throw new ApiError(403, "无权访问该图片。");
        Path path = root.resolve(id).normalize();
        require(path.startsWith(root), "路径无效。");
        if (!Files.exists(path))
            throw new ApiError(404, "图片文件不存在。");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(s(a, "media_type")))
                .cacheControl(CacheControl.noStore()).body(Files.readAllBytes(path));
    }
}

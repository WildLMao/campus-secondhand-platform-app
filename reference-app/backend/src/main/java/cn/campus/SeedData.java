package cn.campus;

import static cn.campus.Store.p;
import static cn.campus.MarketService.*;
import java.nio.file.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.springframework.boot.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
public class SeedData implements ApplicationRunner {
    final Store db;
    final MarketService service;
    final boolean enabled;
    final Path root;

    SeedData(Store db, MarketService service, @Value("${app.seed}") boolean enabled,
            @Value("${app.upload-dir}") String root) {
        this.db = db;
        this.service = service;
        this.enabled = enabled;
        this.root = Path.of(root);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!enabled || db.one("select id from accounts limit 1", p()) != null)
            return;
        String seller = "";
        for (String name : new String[] { "admin", "seller", "buyer", "buyer2" }) {
            String uid = id();
            db.exec("insert into accounts(id,username,password_hash,nickname,role,verified,restricted,created_at) values(#{p.id},#{p.u},#{p.p},#{p.n},#{p.r},1,0,#{p.t})",
                    p("id", uid, "u", name, "p",
                            new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("Campus123!"),
                            "n", switch (name) {
                                case "admin" -> "校园管理员";
                                case "seller" -> "小林同学";
                                case "buyer" -> "买方甲";
                                default -> "买方乙";
                            }, "r", name.equals("admin") ? "ADMIN" : "USER", "t", now()));
            if (name.equals("seller"))
                seller = uid;
        }
        String[] titles = { "高等数学 · 同济第七版", "USB 充电阅读台灯", "羽毛球拍 · 双拍套装", "铝合金笔记本支架" };
        String[] categories = { "教材书籍", "宿舍生活", "运动户外", "数码配件" };
        String[] prices = { "25.00", "39.00", "48.00", "32.00" };
        String[] captions = { "MATHEMATICS", "READING LIGHT", "BADMINTON", "LAPTOP STAND" };
        for (int k = 0; k < titles.length; k++) {
            String asset = id();
            BufferedImage image = new BufferedImage(900, 560, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color[] { new Color(223, 234, 215), new Color(242, 230, 205), new Color(220, 233, 240),
                    new Color(236, 222, 217) }[k]);
            g.fillRect(0, 0, 900, 560);
            g.setColor(new Color(49, 91, 69));
            g.setFont(new Font("SansSerif", Font.BOLD, 30));
            g.drawString("CAMPUS / 0" + (k + 1), 60, 75);
            g.setFont(new Font("Serif", Font.BOLD, 190));
            g.drawString(new String[] { "M", "L", "B", "S" }[k], 340, 350);
            g.setFont(new Font("SansSerif", Font.PLAIN, 26));
            g.drawString(captions[k], 60, 500);
            g.dispose();
            ImageIO.write(image, "png", root.resolve(asset).toFile());
            db.exec("insert into assets(id,owner_id,kind,media_type,created_at) values(#{p.id},#{p.u},'ITEM','image/png',#{p.t})",
                    p("id", asset, "u", seller, "t", now()));
            service.saveItem(seller, null, p("title", titles[k], "category", categories[k], "description",
                    "课程演示物品，保存完好。请在订单会话约定校内交接，款项线下结算。", "price", prices[k], "image_id", asset));
        }
        db.exec("update items set status='在售'", p());
    }
}

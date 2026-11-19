import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;

/** PNG 转 JPEG：java ToJpeg.java in.png out.jpg [quality] */
public class ToJpeg {
    public static void main(String[] args) throws Exception {
        BufferedImage source = ImageIO.read(new File(args[0]));
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        rgb.getGraphics().drawImage(source, 0, 0, null);
        float quality = args.length > 2 ? Float.parseFloat(args[2]) : 0.8f;
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        try (FileImageOutputStream out = new FileImageOutputStream(new File(args[1]))) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(rgb, null, null), param);
        }
        writer.dispose();
    }
}

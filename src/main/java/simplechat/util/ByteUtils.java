package simplechat.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import simplechat.SimpleChatApplication;

import javax.imageio.ImageIO;
import javax.servlet.ServletContext;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.security.MessageDigest;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * @author masoud
 */
@Component
public class ByteUtils {

    @Autowired
    private ServletContext servletContext;

    private HashMap<String, Long> lastModified = new HashMap<>();
    private HashMap<String, byte[]> cachedBytes = new HashMap<>();

    public MediaType getMediaType(String fileName) {
        try {
            String mimeType = servletContext.getMimeType(fileName);
            MediaType mediaType = MediaType.parseMediaType(mimeType);
            return mediaType;
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    public String hash(String s) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] b = digest.digest(s.getBytes("UTF-8"));
            return toHex(b, "");
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return "";
    }

    public String toHex(byte b[], String delimeter) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            String h = String.format("%h", b[i] & 0xff);
            if (h.length() == 1) {
                h = "0" + h;
            }
            sb.append((i == 0) ? h : (delimeter + h));
        }
        return sb.toString();
    }

    public String readString(File file) throws IOException {
        return new String(readBytes(file, false), "UTF-8");
    }

    public synchronized byte[] readBytes(File file, boolean skipCaching) throws IOException {
        String path = file.getAbsolutePath();
        long lm = file.lastModified();
        if (cachedBytes.containsKey(path)) {
            if (lm == lastModified.get(path)) {
                return cachedBytes.get(path);
            }
        }
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        copy(new FileInputStream(file), os, true, true);
        byte b[] = os.toByteArray();
        if (!skipCaching) {
            lastModified.put(path, lm);
            cachedBytes.put(path, b);
        }
        return b;
    }

    public void copy(InputStream is, OutputStream os, boolean closeInput, boolean closeOutput) throws IOException {
        byte b[] = new byte[10000];
        while (true) {
            int r = is.read(b);
            if (r < 0) {
                break;
            }
            os.write(b, 0, r);
        }
        if (closeInput) {
            is.close();
        }
        if (closeOutput) {
            os.flush();
            os.close();
        }
    }

    public String serializeException(Exception ex) {
        try {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            PrintWriter writer = null;
            writer = new PrintWriter(new OutputStreamWriter(os, "UTF-8"));
            ex.printStackTrace(writer);
            writer.close();
            return new String(os.toByteArray(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return "Unable to serialize";
        }
    }

    public String readPage(String fileName, Map<String, String> params) throws IOException {
        String page = readString(new File(SimpleChatApplication.pageResourcePath + fileName));
        int si = 0, ei = -2;
        StringBuilder sb = new StringBuilder();
        while (true) {
            int oei = ei;
            si = page.indexOf("<%", ei + 2);
            if (si < 0) {
                break;
            }
            ei = page.indexOf("%>", si);
            String cmd = page.substring(si + 2, ei).trim();
            String rep;
            switch (cmd.charAt(0)) {
                case '$':
                    rep = params.get(cmd.substring(1));
                    break;
                case '#':
                    rep = readPage("/" + cmd.substring(1), params);
                    break;
                default:
                    throw new IOException("Invalid identifier");
            }
            sb.append(page, oei + 2, si);
            sb.append(rep);
        }
        sb.append(page, ei + 2, page.length());
        return sb.toString();
    }

    public String humanReadableSize(long len) {
        double size = 0;
        String fix = null;
        if (len < 1024) {
            size = len;
            fix = "B";
        } else if (len < 1024L * 1024L) {
            size = len / 1024.0;
            fix = "KiB";
        } else if (len < 1024L * 1024L * 1024L) {
            size = len / 1024.0 / 1024.0;
            fix = "MiB";
        } else if (len < 1024L * 1024L * 1024L * 1024L) {
            size = len / 1024.0 / 1024.0 / 1024.0;
            fix = "GiB";
        } else if (len < 1024L * 1024L * 1024L * 1024L * 1024L) {
            size = len / 1024.0 / 1024.0 / 1024.0 / 1024.0;
            fix = "TiB";
        } else if (len < 1024L * 1024L * 1024L * 1024L * 1024L * 1024L) {
            size = len / 1024.0 / 1024.0 / 1024.0 / 1024.0 / 1024.0;
            fix = "PiB";
        }
        if (fix == null) {
            return "Too Big";
        } else {
            String sizeStr = String.format("%.3f", size);
            while (sizeStr.endsWith("0")) {
                sizeStr = sizeStr.substring(0, sizeStr.length() - 1);
            }
            if (sizeStr.endsWith(".")) {
                sizeStr = sizeStr.substring(0, sizeStr.length() - 1);
            }
            return sizeStr + " " + fix;
        }
    }

    public String formatTime(long t) {
        Date d = new Date(t);
        DateFormat formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault());
        return formatter.format(d);
    }

    public void makeThumbnailImage(String format, int width, InputStream input, OutputStream output, boolean closeInputStream, boolean closeOutputStream) throws IOException {
        BufferedImage orgImg = ImageIO.read(input);
        if (orgImg == null) {
            return;
        }
        int w = Math.min(width, orgImg.getWidth());
        int h = (int) ((double) orgImg.getHeight() / orgImg.getWidth() * w);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        img.createGraphics().drawImage(orgImg.getScaledInstance(w, h, Image.SCALE_SMOOTH), 0, 0, null);
        ImageIO.write(img, format, output);
        if (closeOutputStream) {
            output.flush();
            output.close();
        }
        if (closeInputStream) {
            input.close();
        }
    }
}

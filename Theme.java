import javax.swing.*;
import java.awt.Color;
import java.awt.Font;

/**
 * Old-rose colour palette shared by the client and the server window.
 * Call Theme.apply() once, before any window is created.
 */
public final class Theme {

    private Theme() { }

    public static final Color OLD_ROSE   = new Color(0xC08081); // main brand colour
    public static final Color DEEP_ROSE  = new Color(0x8E4A55); // top bar, headings, selection
    public static final Color WINE       = new Color(0x6B3340); // darkest accent / text on light
    public static final Color DUSTY_PINK = new Color(0xD9A5AB); // borders, progress bars
    public static final Color BLUSH      = new Color(0xF3DDE0); // panel background
    public static final Color CREAM      = new Color(0xFFF7F5); // text areas, inputs
    public static final Color INK        = new Color(0x4A2A30); // normal text
    public static final Color ERROR      = new Color(0xA3162F); // error messages
    public static final Color OK         = new Color(0x6B3340); // normal status messages
    public static final Color HEADING    = new Color(0x8E4A55); // titles and the study timer
    public static final Color MUTED      = new Color(0x8A6E73); // "(edited)", quotes, time stamps
    public static final Color PRIVATE    = new Color(0x7A3E9D); // private messages
    public static final Color ANNOUNCE   = new Color(0xB35900); // host announcements
    public static final Color BAR_TEXT   = new Color(0xFFF7F5); // text on the deep-rose top bar

    public static final Font CHAT_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 14);

    /** One colour per user so you can tell who wrote what (the server hands out the numbers). */
    private static final Color[] NAME_COLORS = {
        new Color(0x1F6FD0), new Color(0xD33F49), new Color(0x2E8B57), new Color(0xC77D0A), new Color(0x8E44AD),
        new Color(0x00897B), new Color(0xC2185B), new Color(0x4F5BD5), new Color(0x6A9A1F), new Color(0xE05A2B)
    };

    public static Color userColor(int index) {
        return NAME_COLORS[Math.floorMod(index, NAME_COLORS.length)];
    }

    /** Switches to the Nimbus look and feel and re-colours it in old rose. */
    public static void apply() {
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (Exception ignored) {
            // fall back to the default look and feel; colours below are then partly ignored
        }
        UIManager.put("control", BLUSH);
        UIManager.put("nimbusBase", OLD_ROSE);
        UIManager.put("nimbusBlueGrey", DUSTY_PINK);
        UIManager.put("nimbusLightBackground", CREAM);
        UIManager.put("nimbusOrange", OLD_ROSE);          // progress bars
        UIManager.put("nimbusFocus", DEEP_ROSE);
        UIManager.put("nimbusSelectionBackground", DEEP_ROSE);
        UIManager.put("nimbusSelectedText", Color.WHITE);
        UIManager.put("text", INK);
        UIManager.put("textForeground", INK);
        UIManager.put("TitledBorder.titleColor", DEEP_ROSE);
        UIManager.put("OptionPane.background", BLUSH);
        UIManager.put("Panel.background", BLUSH);
    }
}

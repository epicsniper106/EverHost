package dev.everhost.install;

import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;

/** Visible after Minecraft closes, including during a large first-time download. */
final class ModInstallWindow implements AutoCloseable {
    private JFrame frame;
    private JLabel detail;
    private JProgressBar bar;

    static ModInstallWindow open(int total) {
        ModInstallWindow window = new ModInstallWindow();
        if (!GraphicsEnvironment.isHeadless()) {
            try {
                SwingUtilities.invokeAndWait(() -> {
                    window.frame = new JFrame("EverHost - Installing Mods");
                    window.detail = new JLabel("Preparing downloads. Keep Minecraft closed.");
                    window.bar = new JProgressBar(0, total);
                    window.bar.setStringPainted(true);
                    JPanel body = new JPanel(new BorderLayout(0, 14));
                    body.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
                    body.add(new JLabel("EverHost is installing the server's exact mod files"), BorderLayout.NORTH);
                    body.add(window.detail, BorderLayout.CENTER);
                    body.add(window.bar, BorderLayout.SOUTH);
                    window.frame.setContentPane(body);
                    window.frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                    window.frame.setSize(650, 170);
                    window.frame.setLocationRelativeTo(null);
                    window.frame.setVisible(true);
                });
            } catch (Exception unavailable) { window.close(); }
        }
        return window;
    }

    void update(int completed, String text) {
        if (frame != null) SwingUtilities.invokeLater(() -> {
            detail.setText(text);
            bar.setValue(completed);
        });
    }

    @Override public void close() {
        if (frame != null) SwingUtilities.invokeLater(() -> frame.dispose());
    }
}

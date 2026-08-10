package com.threebody.swing;

import com.threebody.core.BodySpec;
import com.threebody.core.BodyState;
import com.threebody.core.ConfigValidator;
import com.threebody.core.Metrics;
import com.threebody.core.MetricsCalculator;
import com.threebody.core.NBodyIntegrator;
import com.threebody.core.NearEncounter;
import com.threebody.core.NumericalInstabilityException;
import com.threebody.core.Preset;
import com.threebody.core.PresetKey;
import com.threebody.core.Presets;
import com.threebody.core.SimulationConfig;
import com.threebody.core.SimulationState;
import com.threebody.core.StepResult;
import com.threebody.core.Vector3;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 三体运动模拟 Swing 适配器 —— 渲染与输入控制。
 *
 * <p>
 * 物理计算全部委托 {@link NBodyIntegrator} 与 {@link MetricsCalculator}；
 * 不再保留重复的 RK4 或引力逻辑。
 * </p>
 */
public final class ThreeBodySwingAdapter extends JPanel {

    private static final int WINDOW_WIDTH = 1920;
    private static final int WINDOW_HEIGHT = 1080;

    // ============================ 当前模拟状态 ============================
    private SimulationConfig config;
    private SimulationState state;
    private List<List<Point>> trails;
    private int stepCount;
    private final javax.swing.Timer timer;

    // ============================ 视图控制 ============================
    private double scale = 8e8;   // m → px
    private double zoom = 1.0;
    private double offsetX, offsetY;
    private Point dragStart;
    private boolean showTrails = true;

    // ============================ 主题 ============================
    private enum Theme { NIGHT, STAR, COLOR, SCI_FI }
    private Theme currentTheme = Theme.NIGHT;
    private final List<Particle> particles = new ArrayList<>();
    private final Random random = new Random();
    private int planIndex;

    // 全屏
    private boolean isFullScreen;
    private GraphicsDevice device;
    private Dimension originalSize;
    private Point originalLocation;

    public ThreeBodySwingAdapter() {
        setPreferredSize(new Dimension(WINDOW_WIDTH, WINDOW_HEIGHT));
        setBackground(Color.BLACK);
        device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        initParticles();
        switchPlan(0);
        setupKeyBindings();
        setupMouseControls();
        timer = new javax.swing.Timer(1, e -> tick());
    }

    // ============================ 预设方案 ============================

    private void switchPlan(int index) {
        planIndex = index;
        PresetKey key = PresetKey.values()[index];
        Preset preset = Presets.byKey(key);
        SimulationConfig raw = preset.config();
        // 规范化补齐 ID
        var vr = ConfigValidator.validate(raw);
        if (vr.valid() && vr.normalizedConfig() != null) {
            config = vr.normalizedConfig();
        } else {
            config = raw;
        }
        state = NBodyIntegrator.initialState(config);
        stepCount = 0;
        if (trails == null) trails = new ArrayList<>();
        else trails.clear();
        for (int i = 0; i < config.bodyCount(); i++) {
            trails.add(new ArrayList<>());
        }
        repaint();
    }

    // ============================ 模拟步进（委托 core） ============================

    private void tick() {
        try {
            StepResult result = NBodyIntegrator.step(config, state);
            state = result.state();
            stepCount++;
            appendTrails(state);
            updateParticles();
            repaint();
        } catch (NumericalInstabilityException ex) {
            timer.stop();
            System.err.println("[Swing] 数值不稳定：" + ex.getMessage());
        }
    }

    private void appendTrails(SimulationState st) {
        List<BodyState> bodies = st.bodies();
        for (int i = 0; i < bodies.size(); i++) {
            BodyState b = bodies.get(i);
            int px = (int) ((b.position().x() / scale) * zoom + getWidth() / 2.0 + offsetX);
            int py = (int) ((b.position().y() / scale) * zoom + getHeight() / 2.0 + offsetY);
            trails.get(i).add(new Point(px, py));
            if (trails.get(i).size() > 2000) trails.get(i).remove(0);
        }
    }

    // ============================ 渲染 ============================

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // 背景
        drawBackground(g2d);

        // 轨迹
        if (showTrails && state != null) {
            g2d.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            List<BodySpec> specs = config.bodies();
            for (int i = 0; i < trails.size(); i++) {
                List<Point> trail = trails.get(i);
                Color c = parseColor(specs.get(i).color());
                for (int j = 1; j < trail.size(); j++) {
                    float alpha = (float) j / trail.size();
                    g2d.setColor(new Color(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, alpha));
                    Point p1 = trail.get(j - 1);
                    Point p2 = trail.get(j);
                    g2d.drawLine(p1.x, p1.y, p2.x, p2.y);
                }
            }
        }

        // 天体
        if (state != null) {
            for (BodyState b : state.bodies()) {
                BodySpec spec = findSpec(b.id());
                Color c = spec != null ? parseColor(spec.color()) : Color.WHITE;
                g2d.setColor(c);
                int size = 10;
                int px = (int) ((b.position().x() / scale) * zoom + getWidth() / 2.0 + offsetX);
                int py = (int) ((b.position().y() / scale) * zoom + getHeight() / 2.0 + offsetY);
                g2d.fill(new Ellipse2D.Double(px - size / 2.0, py - size / 2.0, size, size));
            }
        }

        // 粒子
        for (Particle p : particles) {
            g2d.setColor(Color.WHITE);
            g2d.fillRect((int) p.x, (int) p.y, 2, 2);
        }

        drawHUD(g2d);
    }

    private void drawBackground(Graphics2D g2d) {
        switch (currentTheme) {
            case NIGHT -> { g2d.setColor(Color.BLACK); g2d.fillRect(0, 0, getWidth(), getHeight()); }
            case STAR -> {
                g2d.setColor(Color.BLACK); g2d.fillRect(0, 0, getWidth(), getHeight());
                g2d.setColor(Color.WHITE);
                for (int i = 0; i < 300; i++) g2d.fillRect(random.nextInt(getWidth()), random.nextInt(getHeight()), 1, 1);
            }
            case COLOR -> { g2d.setColor(Color.DARK_GRAY); g2d.fillRect(0, 0, getWidth(), getHeight()); }
            case SCI_FI -> {
                GradientPaint gp = new GradientPaint(0, 0, Color.MAGENTA, getWidth(), getHeight(), Color.CYAN);
                g2d.setPaint(gp); g2d.fillRect(0, 0, getWidth(), getHeight());
            }
        }
    }

    private void drawHUD(Graphics2D g2d) {
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("Consolas", Font.PLAIN, 16));
        int y = 20;
        g2d.drawString("Step: " + stepCount, 10, y); y += 20;
        g2d.drawString("Bodies: " + (state != null ? state.bodies().size() : 0), 10, y); y += 20;
        g2d.drawString("Trails: " + (showTrails ? "ON" : "OFF") + " | Zoom: " + String.format("%.2f", zoom), 10, y); y += 20;
        g2d.drawString("Plan: " + (char) ('A' + planIndex), 10, y); y += 20;
        g2d.drawString("Theme: " + currentTheme.name(), 10, y);

        // 指标（如果已运行）
        if (state != null && stepCount > 0) {
            try {
                double e = MetricsCalculator.totalEnergy(config, state);
                y += 20;
                g2d.drawString(String.format("Total Energy: %.4e J", e), 10, y);
            } catch (Exception ignored) {}
        }
    }

    // ============================ 粒子 ============================

    private void initParticles() {
        particles.clear();
        for (int i = 0; i < 200; i++) {
            particles.add(new Particle(random.nextDouble() * WINDOW_WIDTH, random.nextDouble() * WINDOW_HEIGHT,
                    random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1, 1 + random.nextDouble() * 2));
        }
    }

    private void updateParticles() {
        for (Particle p : particles) {
            p.x += p.vx;
            p.y += p.vy;
            if (p.x < 0 || p.x > WINDOW_WIDTH) p.vx *= -1;
            if (p.y < 0 || p.y > WINDOW_HEIGHT) p.vy *= -1;
        }
    }

    // ============================ 键盘 ============================

    private void setupKeyBindings() {
        InputMap im = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getActionMap();
        im.put(KeyStroke.getKeyStroke("F11"), "fullscreen");
        am.put("fullscreen", new AbstractAction() { public void actionPerformed(ActionEvent e) { toggleFullScreen(); } });
        im.put(KeyStroke.getKeyStroke("ESCAPE"), "exitFullscreen");
        am.put("exitFullscreen", new AbstractAction() { public void actionPerformed(ActionEvent e) { if (isFullScreen) exitFullScreen(); } });
        im.put(KeyStroke.getKeyStroke("SPACE"), "pause");
        am.put("pause", new AbstractAction() { public void actionPerformed(ActionEvent e) {
            if (timer.isRunning()) timer.stop(); else timer.start();
        }});
        im.put(KeyStroke.getKeyStroke("T"), "toggleTrails");
        am.put("toggleTrails", new AbstractAction() { public void actionPerformed(ActionEvent e) { showTrails = !showTrails; } });
        im.put(KeyStroke.getKeyStroke("L"), "clearTrails");
        am.put("clearTrails", new AbstractAction() { public void actionPerformed(ActionEvent e) { trails.forEach(List::clear); } });
        for (int i = 1; i <= 4; i++) {
            final int themeIdx = i - 1;
            im.put(KeyStroke.getKeyStroke(String.valueOf(i)), "theme" + i);
            am.put("theme" + i, new AbstractAction() { public void actionPerformed(ActionEvent e) {
                currentTheme = Theme.values()[themeIdx];
            }});
        }
        for (char key : new char[]{'A', 'B', 'C', 'D'}) {
            int idx = key - 'A';
            im.put(KeyStroke.getKeyStroke(String.valueOf(key)), "plan" + key);
            am.put("plan" + key, new AbstractAction() { public void actionPerformed(ActionEvent e) {
                timer.stop();
                switchPlan(idx);
                timer.start();
            }});
        }
    }

    // ============================ 鼠标 ============================

    private void setupMouseControls() {
        addMouseWheelListener(e -> {
            double delta = e.getPreciseWheelRotation();
            zoom *= (1 - delta * 0.1);
            zoom = Math.max(0.1, Math.min(10, zoom));
            trails.forEach(List::clear);
        });
        addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) { dragStart = e.getPoint(); }
            public void mouseReleased(MouseEvent e) { dragStart = null; }
        });
        addMouseMotionListener(new MouseAdapter() {
            public void mouseDragged(MouseEvent e) {
                if (dragStart != null) {
                    trails.forEach(List::clear);
                    Point cur = e.getPoint();
                    offsetX += cur.x - dragStart.x;
                    offsetY += cur.y - dragStart.y;
                    dragStart = cur;
                }
            }
        });
    }

    // ============================ 全屏 ============================

    private void toggleFullScreen() {
        if (isFullScreen) exitFullScreen(); else enterFullScreen();
    }

    private void enterFullScreen() {
        JFrame frame = (JFrame) SwingUtilities.getWindowAncestor(this);
        if (device.isFullScreenSupported()) {
            originalSize = frame.getSize();
            originalLocation = frame.getLocation();
            frame.dispose();
            frame.setUndecorated(true);
            device.setFullScreenWindow(frame);
            isFullScreen = true;
            frame.setVisible(true);
        } else {
            frame.dispose();
            frame.setUndecorated(true);
            frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            isFullScreen = true;
            frame.setVisible(true);
        }
    }

    private void exitFullScreen() {
        JFrame frame = (JFrame) SwingUtilities.getWindowAncestor(this);
        frame.dispose();
        frame.setUndecorated(false);
        if (device.isFullScreenSupported()) device.setFullScreenWindow(null);
        if (originalSize != null) frame.setSize(originalSize);
        if (originalLocation != null) frame.setLocation(originalLocation);
        else frame.setLocationRelativeTo(null);
        isFullScreen = false;
        frame.setVisible(true);
    }

    // ============================ 实用方法 ============================

    private BodySpec findSpec(String id) {
        for (BodySpec s : config.bodies()) {
            if (s.id().equals(id)) return s;
        }
        return null;
    }

    private static Color parseColor(String hex) {
        if (hex == null || !hex.startsWith("#") || hex.length() != 7) return Color.WHITE;
        try {
            return new Color(Integer.parseInt(hex.substring(1), 16));
        } catch (NumberFormatException e) {
            return Color.WHITE;
        }
    }

    public void start() { timer.start(); }
    public void stop() { timer.stop(); }

    // ============================ 内部类型 ============================

    private static class Particle {
        double x, y, vx, vy, size;
        Particle(double x, double y, double vx, double vy, double size) {
            this.x = x; this.y = y; this.vx = vx; this.vy = vy; this.size = size;
        }
    }

    // ============================ 独立入口 ============================

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("ThreeBodySimulation — Swing Adapter");
            ThreeBodySwingAdapter panel = new ThreeBodySwingAdapter();
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.add(panel);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            panel.start();
        });
    }
}

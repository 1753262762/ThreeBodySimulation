import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.swing.*;

/**
 * ThreeBodySimulationProject - 三体运动模拟大作业
 * 
 * 功能特性:
 * - 基于RK4积分方法的三体运动物理模拟
 * - 四种视觉主题切换:黑夜、星空、科幻、彩色
 * - 实时HUD显示:模拟步数、速度、天体数量等信息
 * - 动态粒子背景效果
 * - 可开关的轨迹显示,支持颜色渐变
 * - 鼠标滚轮缩放、拖拽平移视图
 * - 空格键暂停/继续模拟
 * - F11全屏切换
 * - A/B/C/D四种初始天体配置方案
 */
public class ThreeBodySimulation extends JPanel {

    // ============================ 窗口和渲染参数 ============================
    private static final int WINDOW_WIDTH = 1920; // 窗口默认宽度
    private static final int WINDOW_HEIGHT = 1080; // 窗口默认高度

    // ============================ 全屏相关变量 ============================
    private boolean isFullScreen = false;
    // 全屏状态标志
    private GraphicsDevice device;
    // 图形设备,用于全屏控制
    private Dimension originalSize;
    // 退出全屏时恢复的窗口大小
    private Point originalLocation;
    // 退出全屏时恢复的窗口位置

    // ============================ 三体模拟物理参数 ============================
    private static final double G = 6.67430e-11;
    // 万有引力常数
    private double dt = 43200;
    // 时间步长: 12小时(43200秒)
    private double scale = 4e9 * 0.2;
    // 坐标缩放比例,将天文单位转换为像素
    private List<Body> bodies;
    // 天体对象列表
    private Timer timer;
    // 模拟更新定时器
    private List<List<Point>> trails;
    // 每个天体的运动轨迹点列表
    private int stepCount = 0;
    // 模拟步数计数器

    // ============================ 主题和视觉效果参数 ============================
    /**
     * 视觉主题枚举
     * NIGHT - 纯黑背景
     * STAR - 星空背景
     * COLOR - 深灰背景
     * SCI_FI - 科幻渐变背景
     */
    private enum Theme {
        NIGHT, STAR, COLOR, SCI_FI
    }

    private Theme currentTheme = Theme.NIGHT; // 当前主题
    private boolean showTrails = true; // 是否显示轨迹标志

    // 粒子效果系统
    private List<Particle> particles = new ArrayList<>(); // 粒子列表
    private Random random = new Random(); // 随机数生成器

    // 视图控制参数
    private double zoom = 1.0; // 缩放级别
    private Point dragStart = null; // 拖拽起始点
    private double offsetX = 0, offsetY = 0; // 视图偏移量

    // 当前使用的初始方案索引 (0:A, 1:B, 2:C, 3:D)
    private int planIndex = 0;

    /**
     * 构造函数 - 初始化模拟环境
     */
    public ThreeBodySimulation() {
        // 设置面板大小和背景
        setPreferredSize(new Dimension(WINDOW_WIDTH, WINDOW_HEIGHT));
        setBackground(Color.BLACK);

        // 初始化天体系统和粒子效果
        initializeBodies();
        initializeParticles();

        // 创建定时器,每16毫秒(~60FPS)更新一次模拟
        timer = new Timer(1, e -> {
            updateBodies(); // 更新天体位置
            stepCount++; // 增加步数计数
            updateParticles(); // 更新粒子效果
            repaint(); // 重绘界面
        });

        // 获取默认图形设备用于全屏控制
        device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        // 设置键盘快捷键和鼠标控制
        setupKeyBindings();
        setupMouseControls();
    }

    /**
     * 专门清除所有天体的轨迹,但不重置天体位置
     */
    private void clearTrails() {
        if (trails != null) {
            for (List<Point> trail : trails) {
                trail.clear(); // 清空每个天体的轨迹列表
            }
        }
    }

    // ============================ 键盘快捷键设置 ============================
    /**
     * 设置所有键盘快捷键绑定
     */
    private void setupKeyBindings() {
        InputMap im = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getActionMap();

        // F11 - 切换全屏/窗口模式
        im.put(KeyStroke.getKeyStroke("F11"), "toggleFullscreen");
        am.put("toggleFullscreen", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                toggleFullScreen();
            }
        });

        // ESC - 退出全屏模式
        im.put(KeyStroke.getKeyStroke("ESCAPE"), "exitFullscreen");
        am.put("exitFullscreen", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (isFullScreen)
                    exitFullScreen();
            }
        });

        // 空格键 - 暂停/继续模拟
        im.put(KeyStroke.getKeyStroke("SPACE"), "pauseResume");
        am.put("pauseResume", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (timer.isRunning())
                    timer.stop();
                else
                    timer.start();
            }
        });
        // 按L键清除轨迹
        im.put(KeyStroke.getKeyStroke("L"), "clearTrails");
        am.put("clearTrails", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                clearTrails();
            }
        });
        // T键 - 切换轨迹显示
        im.put(KeyStroke.getKeyStroke("T"), "toggleTrails");
        am.put("toggleTrails", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showTrails = !showTrails;
            }
        });

        // 数字键1-4 - 切换视觉主题
        im.put(KeyStroke.getKeyStroke("1"), "themeNight");
        am.put("themeNight", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                currentTheme = Theme.NIGHT;
            }
        });
        im.put(KeyStroke.getKeyStroke("2"), "themeStar");
        am.put("themeStar", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                currentTheme = Theme.STAR;
            }
        });
        im.put(KeyStroke.getKeyStroke("3"), "themeColor");
        am.put("themeColor", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                currentTheme = Theme.COLOR;
            }
        });
        im.put(KeyStroke.getKeyStroke("4"), "themeSciFi");
        am.put("themeSciFi", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                currentTheme = Theme.SCI_FI;
            }
        });

        // A-D键 - 切换初始天体配置方案
        im.put(KeyStroke.getKeyStroke("A"), "planA");
        am.put("planA", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                switchPlan(0);
            }
        });
        im.put(KeyStroke.getKeyStroke("B"), "planB");
        am.put("planB", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                switchPlan(1);
            }
        });
        im.put(KeyStroke.getKeyStroke("C"), "planC");
        am.put("planC", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                switchPlan(2);
            }
        });
        im.put(KeyStroke.getKeyStroke("D"), "planD");
        am.put("planD", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                switchPlan(3);
            }
        });
    }

    // ============================ 鼠标控制设置 ============================
    /**
     * 设置鼠标滚轮缩放和拖拽控制
     */
    private void setupMouseControls() {
        // 鼠标滚轮缩放
        addMouseWheelListener(e -> {
            double delta = e.getPreciseWheelRotation();
            // 根据滚轮方向调整缩放级别
            zoom *= (1 - delta * 0.1);
            // 限制缩放范围在0.1-10倍之间
            if (zoom < 0.1)
                zoom = 0.1;
            else if (zoom > 10)
                zoom = 10;
            else
                clearTrails();
        });

        // 鼠标拖拽平移
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragStart = e.getPoint(); // 记录拖拽起始点
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragStart = null; // 清除拖拽状态
            }
        });

        addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragStart != null) {
                    clearTrails();
                    Point current = e.getPoint();
                    // 计算拖拽偏移并更新视图位置
                    offsetX += current.x - dragStart.x;
                    offsetY += current.y - dragStart.y;
                    dragStart = current;
                }
            }
        });
    }

    // ============================ 全屏操作 ============================
    /**
     * 切换全屏/窗口模式
     */
    private void toggleFullScreen() {
        if (isFullScreen)
            exitFullScreen();
        else
            enterFullScreen();
    }

    /**
     * 进入全屏模式
     */
    private void enterFullScreen() {
        JFrame frame = (JFrame) SwingUtilities.getWindowAncestor(this);
        if (device.isFullScreenSupported()) {
            // 保存当前窗口状态
            originalSize = frame.getSize();
            originalLocation = frame.getLocation();

            // 设置全屏
            frame.dispose();
            frame.setUndecorated(true); // 移除窗口装饰
            device.setFullScreenWindow(frame);
            isFullScreen = true;
            frame.setVisible(true);
        } else {
            // 如果不支持真正的全屏,使用最大化窗口模拟
            frame.dispose();
            frame.setUndecorated(true);
            frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            isFullScreen = true;
            frame.setVisible(true);
        }
    }

    /**
     * 退出全屏模式
     */
    private void exitFullScreen() {
        JFrame frame = (JFrame) SwingUtilities.getWindowAncestor(this);
        frame.dispose();
        frame.setUndecorated(false); // 恢复窗口装饰

        if (device.isFullScreenSupported())
            device.setFullScreenWindow(null);

        // 恢复原始窗口大小和位置
        if (originalSize != null)
            frame.setSize(originalSize);
        if (originalLocation != null)
            frame.setLocation(originalLocation);
        else
            frame.setLocationRelativeTo(null); // 居中显示

        isFullScreen = false;
        frame.setVisible(true);
    }

    // ============================ 天体系统初始化 ============================
    /**
     * 初始化天体系统
     */
    private void initializeBodies() {
        if (bodies == null)
            bodies = new ArrayList<>();
        else
            bodies.clear();

        if (trails == null)
            trails = new ArrayList<>();
        else
            trails.clear();

        // 根据当前方案索引初始化天体
        switchPlan(planIndex);
    }

    /**
     * 切换到指定的初始配置方案
     * 
     * @param index 方案索引 (0:A, 1:B, 2:C, 3:D)
     */
    private void switchPlan(int index) {
        planIndex = index;
        bodies.clear();
        trails.clear();

        // 根据索引选择对应的配置方案
        switch (index) {
            case 0:
                planA(); // 方案A: 三体混沌运动
                break;
            case 1:
                planB(); // 方案B: 对称初始配置
                break;
            case 2:
                planC(); // 方案C: 垂直对称配置
                break;
            case 3:
                planD(); // 方案D: 紧凑配置
                break;
        }

        // 为每个天体初始化轨迹列表
        for (Body b : bodies) {
            trails.add(new ArrayList<>());
        }
    }

    // ============================ 四种初始配置方案 ============================
    /**
     * 方案A: 三体混沌运动配置
     * 三个质量相近的天体,形成典型的混沌三体系统
     */
    private void planA() {
        bodies.add(new Body(10.989e30, new Vector3D(2.1e8, 1.2e6, 0), new Vector3D(1919, 1145, 0), Color.YELLOW));
        bodies.add(
                new Body(10.289e30, new Vector3D(2.796e11, 2.796e11, 0), new Vector3D(20000, -20000, 0), Color.BLUE));
        bodies.add(
                new Body(10.289e30, new Vector3D(-2.796e11, -2.796e11, 0), new Vector3D(-20000, 20000, 0), Color.RED));
    }

    /**
     * 方案B: 水平对称配置
     * 三个天体在水平轴上对称分布
     */
    private void planB() {
        bodies.add(new Body(10.989e30, new Vector3D(0, 0, 0), new Vector3D(0, 0, 0), Color.YELLOW));
        bodies.add(new Body(10.289e30, new Vector3D(2.796e11, 0, 0), new Vector3D(0, 20000, 0), Color.BLUE));
        bodies.add(new Body(10.289e30, new Vector3D(-2.796e11, 0, 0), new Vector3D(0, -20000, 0), Color.RED));
    }

    /**
     * 方案C: 垂直对称配置
     * 三个天体在垂直轴上对称分布
     */
    private void planC() {
        bodies.add(new Body(10.989e30, new Vector3D(0, 0, 0), new Vector3D(0, 0, 0), Color.YELLOW));
        bodies.add(new Body(10.289e30, new Vector3D(0, 2.796e11, 0), new Vector3D(-20000, 0, 0), Color.BLUE));
        bodies.add(new Body(10.289e30, new Vector3D(0, -2.796e11, 0), new Vector3D(20000, 0, 0), Color.RED));
    }

    /**
     * 方案D: 紧凑配置
     * 天体间距离较小,运动更为剧烈
     */
    private void planD() {
        bodies.add(new Body(10.989e30, new Vector3D(0, 0, 0), new Vector3D(0, 0, 0), Color.YELLOW));
        bodies.add(new Body(10.289e30, new Vector3D(1.5e11, 1.5e11, 0), new Vector3D(10000, -15000, 0), Color.BLUE));
        bodies.add(new Body(10.289e30, new Vector3D(-1.5e11, -1.5e11, 0), new Vector3D(-10000, 15000, 0), Color.RED));
    }

    // ============================ 粒子效果系统 ============================
    /**
     * 初始化粒子效果
     */
    private void initializeParticles() {
        particles.clear();
        // 创建200个随机粒子
        for (int i = 0; i < 200; i++) {
            particles.add(new Particle(random.nextDouble() * WINDOW_WIDTH,
                    random.nextDouble() * WINDOW_HEIGHT,
                    random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1, 1 + random.nextDouble() * 2));
        }
    }

    /**
     * 更新所有粒子的位置
     */
    private void updateParticles() {
        for (Particle p : particles) {
            // 更新粒子位置
            p.x += p.vx;
            p.y += p.vy;
            // 边界反弹
            if (p.x < 0 || p.x > WINDOW_WIDTH)
                p.vx *= -1;
            if (p.y < 0 || p.y > WINDOW_HEIGHT)
                p.vy *= -1;
        }
    }

    // ============================ 天体运动模拟核心 ============================
    /**
     * 使用RK4(四阶龙格-库塔)方法更新所有天体的位置和速度
     */
    private void updateBodies() {
        // RK4积分方法的四个步骤
        List<Body> k1 = copyBodies(bodies);
        List<Vector3D> a1 = calculateAccelerations(k1);

        List<Body> k2 = new ArrayList<>();
        for (int i = 0; i < bodies.size(); i++) {
            Body b = bodies.get(i);
            Vector3D pos = b.position.add(k1.get(i).velocity.multiply(dt / 2));
            Vector3D vel = b.velocity.add(a1.get(i).multiply(dt / 2));
            k2.add(new Body(b.mass, pos, vel, b.color));
        }
        List<Vector3D> a2 = calculateAccelerations(k2);

        List<Body> k3 = new ArrayList<>();
        for (int i = 0; i < bodies.size(); i++) {
            Body b = bodies.get(i);
            Vector3D pos = b.position.add(k2.get(i).velocity.multiply(dt / 2));
            Vector3D vel = b.velocity.add(a2.get(i).multiply(dt / 2));
            k3.add(new Body(b.mass, pos, vel, b.color));
        }
        List<Vector3D> a3 = calculateAccelerations(k3);

        List<Body> k4 = new ArrayList<>();
        for (int i = 0; i < bodies.size(); i++) {
            Body b = bodies.get(i);
            Vector3D pos = b.position.add(k3.get(i).velocity.multiply(dt));
            Vector3D vel = b.velocity.add(a3.get(i).multiply(dt));
            k4.add(new Body(b.mass, pos, vel, b.color));
        }
        List<Vector3D> a4 = calculateAccelerations(k4);

        // 使用RK4公式更新位置和速度
        for (int i = 0; i < bodies.size(); i++) {
            Body b = bodies.get(i);
            // 位置更新公式
            Vector3D newPos = b.position.add(
                    k1.get(i).velocity.add(k2.get(i).velocity.multiply(2))
                            .add(k3.get(i).velocity.multiply(2))
                            .add(k4.get(i).velocity)
                            .multiply(dt / 6));
            // 速度更新公式
            Vector3D newVel = b.velocity.add(
                    a1.get(i).add(a2.get(i).multiply(2))
                            .add(a3.get(i).multiply(2))
                            .add(a4.get(i))
                            .multiply(dt / 6));
            b.position = newPos;
            b.velocity = newVel;

            // 更新轨迹点
            Point pt = new Point(
                    (int) ((b.position.x / scale) * zoom + WINDOW_WIDTH / 2 + offsetX),
                    (int) ((b.position.y / scale) * zoom + WINDOW_HEIGHT / 2 + offsetY));
            trails.get(i).add(pt);
            // 限制轨迹长度,避免内存过度使用
            if (trails.get(i).size() > 2000)
                trails.get(i).remove(0);
        }
    }

    /**
     * 深拷贝天体列表
     * 
     * @param src 源天体列表
     * @return 拷贝后的新列表
     */
    private List<Body> copyBodies(List<Body> src) {
        List<Body> res = new ArrayList<>();
        for (Body b : src)
            res.add(new Body(b.mass, b.position.copy(), b.velocity.copy(), b.color));
        return res;
    }

    /**
     * 计算所有天体受到的引力加速度
     * 
     * @param blist 天体列表
     * @return 每个天体的加速度向量列表
     */
    private List<Vector3D> calculateAccelerations(List<Body> blist) {
        List<Vector3D> accs = new ArrayList<>();
        // 初始化加速度为零向量
        for (int i = 0; i < blist.size(); i++)
            accs.add(new Vector3D(0, 0, 0));

        // 计算每对天体之间的引力
        for (int i = 0; i < blist.size(); i++) {
            Body b1 = blist.get(i);
            for (int j = i + 1; j < blist.size(); j++) {
                Body b2 = blist.get(j);
                // 计算相对位置向量
                Vector3D r = b2.position.subtract(b1.position);
                double dist = r.length();
                // 避免距离过小导致的数值不稳定
                if (dist < 1e6)
                    continue;
                // 万有引力公式: F = G * m1 * m2 / r^2
                double f = G * b1.mass * b2.mass / (dist * dist);
                Vector3D force = r.normalize().multiply(f);
                // 根据牛顿第二定律计算加速度: a = F/m
                accs.set(i, accs.get(i).add(force.multiply(1.0 / b1.mass)));
                accs.set(j, accs.get(j).add(force.multiply(-1.0 / b2.mass)));
            }
        }
        return accs;
    }

    // ============================ 图形渲染 ============================
    /**
     * 主绘制方法 - 渲染整个模拟场景
     */
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g;
        // 启用抗锯齿,提高图形质量
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2d.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_ENABLE);
        g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

        // 根据当前主题绘制背景
        switch (currentTheme) {
            case NIGHT:
                g2d.setColor(Color.BLACK);
                g2d.fillRect(0, 0, getWidth(), getHeight());
                break;
            case STAR:
                drawStarBackground(g2d);
                break;
            case COLOR:
                g2d.setColor(Color.DARK_GRAY);
                g2d.fillRect(0, 0, getWidth(), getHeight());
                break;
            case SCI_FI:
                drawSciFiBackground(g2d);
                break;
        }

        // 绘制粒子效果
        for (Particle p : particles) {
            g2d.setColor(Color.WHITE);
            g2d.fillRect((int) p.x, (int) p.y, 2, 2);
        }

        // 绘制天体运动轨迹
        if (showTrails) {
            // 设置高质量线条渲染
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            // 使用更平滑的线条笔划
            g2d.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            for (int i = 0; i < trails.size(); i++) {
                List<Point> trail = trails.get(i);
                for (int j = 1; j < trail.size(); j++) {
                    // 计算轨迹透明度渐变(新轨迹更透明,旧轨迹更不透明)
                    float alpha = (float) j / trail.size();
                    g2d.setColor(new Color(bodies.get(i).color.getRed() / 255f, bodies.get(i).color.getGreen() / 255f,
                            bodies.get(i).color.getBlue() / 255f, alpha));
                    Point p1 = trail.get(j - 1);
                    Point p2 = trail.get(j);
                    g2d.drawLine(p1.x, p1.y, p2.x, p2.y);
                }
            }
        }

        // 绘制天体
        for (Body b : bodies) {
            int size = 10; // 天体显示大小
            g2d.setColor(b.color);
            g2d.fill(new Ellipse2D.Double(
                    (b.position.x / scale) * zoom + WINDOW_WIDTH / 2 - size / 2 + offsetX,
                    (b.position.y / scale) * zoom + WINDOW_HEIGHT / 2 - size / 2 + offsetY,
                    size, size));
        }

        // 绘制HUD信息显示
        drawHUD(g2d);
    }

    /**
     * 绘制星空背景
     */
    private void drawStarBackground(Graphics2D g2d) {
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, getWidth(), getHeight());
        g2d.setColor(Color.WHITE);
        // 随机生成300个星星
        for (int i = 0; i < 300; i++) {
            g2d.fillRect(random.nextInt(getWidth()), random.nextInt(getHeight()), 1, 1);
        }
    }

    /**
     * 绘制科幻风格渐变背景
     */
    private void drawSciFiBackground(Graphics2D g2d) {
        // 创建从洋红色到青色的渐变
        GradientPaint gp = new GradientPaint(0, 0, Color.MAGENTA, getWidth(), getHeight(), Color.CYAN);
        g2d.setPaint(gp);
        g2d.fillRect(0, 0, getWidth(), getHeight());
    }

    /**
     * 绘制HUD(Head-Up Display)信息界面
     */

    private void drawHUD(Graphics2D g2d) {
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("Consolas", Font.PLAIN, 16));
        // 显示模拟步数

        g2d.drawString("Step: " + stepCount, 10, 20);
        // 显示天体数量

        g2d.drawString("Bodies: " + bodies.size(), 10, 40);
        // 显示轨迹状态和缩放级别

        g2d.drawString("Trails: " + (showTrails ? "ON" : "OFF") + " | Zoom: " + String.format("%.2f", zoom), 10, 60);
        // 显示当前方案

        g2d.drawString("Plan: " + (char) ('A' + planIndex), 10, 80);
        // 显示当前主题

        g2d.drawString("Theme: " + currentTheme.name(), 10, 100);
        // 显示清除轨迹快捷键

        g2d.drawString("Press L to clear trails", 10, 120);
    }

    // ============================ 程序入口 ============================
    /**
     * 主方法 - 程序启动入口
     */
    public static void main(String[] args) {
        // 创建主窗口
        JFrame frame = new JFrame("ThreeBodySimulationProject");
        ThreeBodySimulation panel = new ThreeBodySimulation();

        // 设置窗口属性
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(panel);
        frame.pack(); // 自动调整窗口大小
        frame.setLocationRelativeTo(null); // 窗口居中显示
        frame.setVisible(true);

        // 启动模拟定时器
        panel.timer.start();
    }

    // ============================ 内部数据类 ============================

    /**
     * 天体质点类 - 表示模拟中的一个天体
     */
    class Body {
        double mass; // 质量(kg)
        Vector3D position; // 位置向量(m)
        Vector3D velocity; // 速度向量(m/s)
        Color color; // 显示颜色

        Body(double m, Vector3D p, Vector3D v, Color c) {
            mass = m;
            position = p;
            velocity = v;
            color = c;
        }
    }

    /**
     * 三维向量类 - 用于表示物理量(位置、速度、加速度等)
     */
    class Vector3D {
        double x, y, z; // 三维坐标分量

        Vector3D(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        // 向量加法
        Vector3D add(Vector3D v) {
            return new Vector3D(x + v.x, y + v.y, z + v.z);
        }

        // 向量减法
        Vector3D subtract(Vector3D v) {
            return new Vector3D(x - v.x, y - v.y, z - v.z);
        }

        // 标量乘法
        Vector3D multiply(double s) {
            return new Vector3D(x * s, y * s, z * s);
        }

        // 向量深拷贝
        Vector3D copy() {
            return new Vector3D(x, y, z);
        }

        // 计算向量长度(模)
        double length() {
            return Math.sqrt(x * x + y * y + z * z);
        }

        // 向量归一化(单位向量)
        Vector3D normalize() {
            double len = length();
            if (len == 0)
                return new Vector3D(0, 0, 0);
            return multiply(1 / len);
        }
    }

    /**
     * 粒子类 - 用于背景粒子效果
     */
    class Particle {
        double x, y; // 位置坐标
        double vx, vy; // 速度分量
        double size; // 粒子大小

        Particle(double x, double y, double vx, double vy, double size) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.size = size;
        }
    }
}
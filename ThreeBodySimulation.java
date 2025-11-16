import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;

// 三体运动模拟的主类,继承自 JPanel
public class ThreeBodySimulation extends JPanel {
    private static final int WIDTH = 980; // 模拟窗口的宽度
    private static final int HEIGHT = 980; // 模拟窗口的高度

    private static final double G = 6.67430e-11; // 万有引力常数
    private static final double DT = 43200*10; // 时间步长: 12小时 (增加步长以观察混沌行为)
    private static final double SCALE = 4e9; // 缩放因子: 适应更大的运动范围
    private List<Body> bodies; // 天体列表
    private final Timer timer; // 定时器,用于更新模拟
    private final List<List<Point>> trails; // 天体轨迹列表
    
    // 构造函数,初始化模拟界面和定时器
    public ThreeBodySimulation() {
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setBackground(Color.BLACK);
        
        initializeBodies();
        
        trails = new ArrayList<>();
        for (int i = 0; i < bodies.size(); i++) {
            trails.add(new ArrayList<>());
        }
        
        timer = new Timer(1, e -> {
            updateBodies();
            repaint();
        });
    }
    
    // 初始化天体,创建一个三体系统
    private void initializeBodies() {
        bodies = new ArrayList<>();
        
        // 三个质量相近的物体,形成三体系统
        // 可以调整初始位置和速度来观察不同的运动模式
        bodies.add(new Body(
            10.989e30, // 质量 (kg)
            new Vector3D(0, 0, 0), // 位置 (m)
            new Vector3D(0, 0, 0), // 速度 (m/s)
            Color.YELLOW
        ));
        
        bodies.add(new Body(
            10.289e30, // 质量 (kg)
            new Vector3D(2.796e11, 2.796e11, 0), // 位置 (m) - 1 AU
            new Vector3D(20000, -20000, 0), // 速度 (m/s)
            Color.BLUE
        ));
        
        bodies.add(new Body(
            10.289e30, // 质量 (kg)
            new Vector3D(-2.796e11, -2.796e11, 0), // 位置 (m) - 1 AU
            new Vector3D(-20000, 20000, 0), // 速度 (m/s)
            Color.BLUE
        ));
        //和谐
        bodies.add(new Body(
            10.989e30, // 质量 (kg)
            new Vector3D(0, 0, 0), // 位置 (m)
            new Vector3D(0, 0, 0), // 速度 (m/s)
            Color.YELLOW
        ));
        bodies.add(new Body(
            10.289e30, // 质量 (kg)
            new Vector3D(2.796e11, 2.796e11, 0), // 位置 (m) - 1 AU
            new Vector3D(20000, -20000, 0), // 速度 (m/s)
            Color.BLUE
        ));
        bodies.add(new Body(
            13.289e30, // 质量 (kg)
            new Vector3D(-2.796e11, -2.796e11, 0), // 位置 (m) - 1 AU
            new Vector3D(-20000, 20000, 0), // 速度 (m/s)
            Color.red
        ));
        //混沌
        bodies.add(new Body(
            10.989e30, // 质量 (kg)
            new Vector3D(0, 0, 0), // 位置 (m)
            new Vector3D(0, 0, 0), // 速度 (m/s)
            Color.YELLOW
        ));
        
        bodies.add(new Body(
            10.289e30, // 质量 (kg)
            new Vector3D(2.796e11, 2.796e11, 0), // 位置 (m) - 1 AU
            new Vector3D(20000, -20000, 0), // 速度 (m/s)
            Color.BLUE
        ));
        
        bodies.add(new Body(
            12.289e30, // 质量 (kg)
            new Vector3D(-2.796e11, -2.796e11, 0), // 位置 (m) - 1 AU
            new Vector3D(-20000, 20000, 0), // 速度 (m/s)
            Color.red
        ));
        //

        bodies.add(new Body(
            10.989e30, // 质量 (kg)
            new Vector3D(0, 0, 0), // 位置 (m)
            new Vector3D(0, 0, 0), // 速度 (m/s)
            Color.YELLOW
        ));

        bodies.add(new Body(10.289e30, // 质量 (kg)
            new Vector3D(2.796e11, 2.796e11, 0), // 位置 (m) - 1 AU
            new Vector3D(20000, -20000, 0), // 速度 (m/s)
            Color.BLUE
        ));
        
        bodies.add(new Body(11.289e30, 
            new Vector3D(-2.796e11, -2.796e11, 0),
            new Vector3D(-20000, 20000, 0),
            Color.red 
        ));
        //

        bodies.add(new Body(2.5e30, // 质量 (kg)
            new Vector3D(-2e11, 1e11, 0), // 位置 (m)
            new Vector3D(1.2e4, -0.8e4, 0), // 速度 (m/s)
            Color.RED
        ));
        
        bodies.add(new Body(
            2.2e30, // 质量 (kg)
            new Vector3D(1.5e11, -1e11, 0), // 位置 (m)
            new Vector3D(-1e4, 1.5e4, 0), // 速度 (m/s)
            Color.BLUE
        ));
        
        bodies.add(new Body(
            2.3e30, // 质量 (kg)
            new Vector3D(0.5e11, 2e11, 0), // 位置 (m)
            new Vector3D(0.5e4, -1.2e4, 0), // 速度 (m/s)
            Color.GREEN
        ));

        bodies.clear();
        bodies.add(new Body(3e30, new Vector3D(-3e11, 0, 0), new Vector3D(0, -2e4, 5e3), Color.RED));
        bodies.add(new Body(3e30, new Vector3D(3e11, 0, 0), new Vector3D(0, 2e4, -5e3), Color.BLUE));
        bodies.add(new Body(3e30, new Vector3D(0, 0, 4e11), new Vector3D(-1e4, 0, 0), Color.GREEN));
        
    }
    
    // 更新天体的位置和速度
    private void updateBodies() {
        // 使用龙格-库塔法更新位置和速度
        List<Body> k1 = new ArrayList<>();
        List<Body> k2 = new ArrayList<>();
        List<Body> k3 = new ArrayList<>();
        List<Body> k4 = new ArrayList<>();
        
        // 计算k1
        for (Body body : bodies) {
            k1.add(new Body(body.mass, body.position, body.velocity, body.color));
        }
        List<Vector3D> k1Accelerations = calculateAccelerations(k1);
        
        // 计算k2
        for (int i = 0; i < bodies.size(); i++) {
            Body body = bodies.get(i);
            Vector3D pos = body.position.add(k1.get(i).velocity.multiply(DT / 2));
            Vector3D vel = body.velocity.add(k1Accelerations.get(i).multiply(DT / 2));
            k2.add(new Body(body.mass, pos, vel, body.color));
        }
        List<Vector3D> k2Accelerations = calculateAccelerations(k2);
        
        // 计算k3
        for (int i = 0; i < bodies.size(); i++) {
            Body body = bodies.get(i);
            Vector3D pos = body.position.add(k2.get(i).velocity.multiply(DT / 2));
            Vector3D vel = body.velocity.add(k2Accelerations.get(i).multiply(DT / 2));
            k3.add(new Body(body.mass, pos, vel, body.color));
        }
        List<Vector3D> k3Accelerations = calculateAccelerations(k3);
        
        // 计算k4
        for (int i = 0; i < bodies.size(); i++) {
            Body body = bodies.get(i);
            Vector3D pos = body.position.add(k3.get(i).velocity.multiply(DT));
            Vector3D vel = body.velocity.add(k3Accelerations.get(i).multiply(DT));
            k4.add(new Body(body.mass, pos, vel, body.color));
        }
        List<Vector3D> k4Accelerations = calculateAccelerations(k4);
        
        // 更新位置和速度
        for (int i = 0; i < bodies.size(); i++) {
            Body body = bodies.get(i);
            
            Vector3D newPos = body.position.add(
                k1.get(i).velocity.add(
                    k2.get(i).velocity.multiply(2).add(
                        k3.get(i).velocity.multiply(2).add(
                            k4.get(i).velocity
                        )
                    )
                ).multiply(DT / 6)
            );
            
            Vector3D newVel = body.velocity.add(
                k1Accelerations.get(i).add(
                    k2Accelerations.get(i).multiply(2).add(
                        k3Accelerations.get(i).multiply(2).add(
                            k4Accelerations.get(i)
                        )
                    )
                ).multiply(DT / 6)
            );
            
            body.position = newPos;
            body.velocity = newVel;
            
            // 更新轨迹
            Point trailPoint = new Point(
                (int)(body.position.x / SCALE) + WIDTH / 2,
                (int)(body.position.y / SCALE) + HEIGHT / 2
            );
            trails.get(i).add(trailPoint);
            
            // 限制轨迹长度
            if (trails.get(i).size() > 2000) {
                trails.get(i).remove(0);
            }
        }
    }
    
    // 计算每个天体的加速度
    private List<Vector3D> calculateAccelerations(List<Body> bodyList) {
        List<Vector3D> accelerations = new ArrayList<>();
        
        for (int i = 0; i < bodyList.size(); i++) {
            accelerations.add(new Vector3D(0, 0, 0));
        }
        
        for (int i = 0; i < bodyList.size(); i++) {
            Body body1 = bodyList.get(i);
            
            for (int j = i + 1; j < bodyList.size(); j++) {
                Body body2 = bodyList.get(j);
                
                Vector3D r = body2.position.subtract(body1.position);
                double distance = r.length();
                double distanceSq = distance * distance;
                
                // 避免除以零
                if (distance < 1e6) continue;
                
                double forceMagnitude = G * body1.mass * body2.mass / distanceSq;
                Vector3D force = r.normalize().multiply(forceMagnitude);
                
                // F = ma, 所以 a = F/m
                Vector3D acceleration1 = force.multiply(1.0 / body1.mass);
                Vector3D acceleration2 = force.multiply(-1.0 / body2.mass);
                
                accelerations.set(i, accelerations.get(i).add(acceleration1));
                accelerations.set(j, accelerations.get(j).add(acceleration2));
            }
        }
        
        return accelerations;
    }
    
    // 重写 paintComponent 方法,绘制天体及其轨迹
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        // 绘制轨迹
        for (int i = 0; i < trails.size(); i++) {
            List<Point> trail = trails.get(i);
            g2d.setColor(bodies.get(i).color); 
            
            for (int j = 1; j < trail.size(); j++) {
                Point p1 = trail.get(j - 1);
                Point p2 = trail.get(j);
                g2d.drawLine(p1.x, p1.y, p2.x, p2.y);
            }
        }
        
        // 绘制天体
        for (int i = 0; i < bodies.size(); i++) {
            Body body = bodies.get(i);
            g2d.setColor(body.color);
            
            int x = (int)(body.position.x / SCALE) + WIDTH / 2;
            int y = (int)(body.position.y / SCALE) + HEIGHT / 2;
            
            // 根据质量计算大小 (对数尺度)
            double size = Math.log(body.mass / 1e24) + 5;
            if (size < 2) size = 2;
            if (size > 20) size = 20;
            
            Ellipse2D.Double circle = new Ellipse2D.Double(x - size /2, y - size/2, size, size);
            g2d.fill(circle);
        }
        
        // 显示信息
        g2d.setColor(Color.WHITE);
        g2d.drawString("三体运动模拟 - 按空格键暂停/继续", 10, 20);
    }
    
    // 开始模拟
    public void startSimulation() {
        timer.start();
    }
    
    // 停止模拟
    public void stopSimulation() {
        timer.stop();
    }
    
    // 主方法,创建并显示模拟窗口
    public static void main(String[] args) {
        JFrame frame = new JFrame("三体运动模拟");
        ThreeBodySimulation simulation = new ThreeBodySimulation();
        
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(simulation);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        
        // 添加键盘控制
        frame.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_SPACE) {
                    if (simulation.timer.isRunning()) {
                        simulation.stopSimulation();
                    } else {
                        simulation.startSimulation();
                    }
                }
            }
        });
        
        frame.setFocusable(true);
        simulation.startSimulation();
    }
    
    // 表示三维向量量辅助类
    static class Vector3D {
        double x, y, z;
        
        public Vector3D(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
        
        public Vector3D add(Vector3D other) {
            return new Vector3D(x + other.x, y + other.y, z + other.z);
        }
        
        public Vector3D subtract(Vector3D other) {
            return new Vector3D(x - other.x, y - other.y, z - other.z);
        }
        
        public Vector3D multiply(double scalar) {
            return new Vector3D(x * scalar, y * scalar, z * scalar);
        }
        
        public double length() {
            return Math.sqrt(x * x + y * y + z * z);
        }
        
        public Vector3D normalize() {
            double len = length();
            if (len == 0) return new Vector3D(0, 0, 0);
            return new Vector3D(x / len, y / len, z / len);
        }
        
        @Override
        public String toString() {
            return String.format("(%.2e, %.2e, %.2e)", x, y, z);
        }
    }
    
    // 表示天体的类
    static class Body {
        double mass;
        Vector3D position;
        Vector3D velocity;
        Color color;
        
        public Body(double mass, Vector3D position, Vector3D velocity, Color color) {
            this.mass = mass;
            this.position = position;
            this.velocity = velocity;
            this.color = color;
        }
    }
}
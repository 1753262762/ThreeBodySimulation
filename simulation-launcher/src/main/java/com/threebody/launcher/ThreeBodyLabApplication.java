package com.threebody.launcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

import java.awt.Desktop;
import java.net.URI;

@SpringBootApplication(scanBasePackages = "com.threebody")
public class ThreeBodyLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(ThreeBodyLabApplication.class, args);
    }

    /**
     * 服务启动后自动打开默认浏览器。使用 {@code ApplicationReadyEvent} 确保
     * 所有 bean 已完成初始化，避免浏览器在服务就绪前连接。
     */
    @EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void openBrowser() {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(new URI("http://127.0.0.1:8721"));
                }
            }
        } catch (Exception e) {
            System.err.println("[ThreeBodyLab] 无法自动打开浏览器：" + e.getMessage());
        }
    }
}

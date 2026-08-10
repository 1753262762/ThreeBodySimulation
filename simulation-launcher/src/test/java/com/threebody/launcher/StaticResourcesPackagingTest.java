package com.threebody.launcher;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class StaticResourcesPackagingTest {

    @Test
    void frontendIndexIsIncludedInLauncherClasspath() {
        assertNotNull(
                getClass().getClassLoader().getResource("static/index.html"),
                "Maven 构建必须先生成并复制前端静态资源");
    }
}

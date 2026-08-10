package com.threebody.core;

/**
 * 天体初始规格。质量单位 kg，位置单位 m，速度单位 m/s。
 *
 * @param id       稳定标识，创建实验时由应用层补齐
 * @param name     显示名称
 * @param color    十六进制颜色 #RRGGBB，仅用于展示
 * @param massKg   质量(kg)
 * @param position 初始位置(m)
 * @param velocity 初始速度(m/s)
 */
public record BodySpec(
        String id,
        String name,
        String color,
        double massKg,
        Vector3 position,
        Vector3 velocity) {

    public BodySpec withId(String newId) {
        return new BodySpec(newId, name, color, massKg, position, velocity);
    }

    public BodySpec withColor(String newColor) {
        return new BodySpec(id, name, newColor, massKg, position, velocity);
    }

    public BodySpec withName(String newName) {
        return new BodySpec(id, newName, color, massKg, position, velocity);
    }
}
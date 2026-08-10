package com.threebody.core;

/**
 * 内置预设。
 *
 * @param key         预设标识
 * @param name        名称
 * @param description 说明
 * @param config      配置
 */
public record Preset(PresetKey key, String name, String description, SimulationConfig config) {
}
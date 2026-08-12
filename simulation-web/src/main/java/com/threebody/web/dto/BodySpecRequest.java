package com.threebody.web.dto;

/**
 * 天体初始规格请求。质量与向量分量使用可空包装类型以区分缺失与 0。
 */
public record BodySpecRequest(
        String id,
        String name,
        String color,
        Double massKg,
        Vector3Request position,
        Vector3Request velocity) {
}

package com.threebody.web.dto;

/**
 * 三维向量请求。分量使用可空包装类型以区分缺失与 0。
 */
public record Vector3Request(Double x, Double y, Double z) {
}

package com.threebody.core;

/**
 * 当积分结果出现 NaN 或无穷值时抛出，由应用层转为 FAILED 状态。
 */
public class NumericalInstabilityException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long step;

    public NumericalInstabilityException(String message, long step) {
        super(message);
        this.step = step;
    }

    public long getStep() {
        return step;
    }
}
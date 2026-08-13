package com.threebody.core;

/**
 * 当积分结果出现 NaN 或无穷值时抛出，由应用层转为 FAILED 状态。
 */
public class NumericalInstabilityException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long step;
    private final String bodyId;
    private final String field;
    private final double simulationTimeSeconds;
    private final String value;

    public NumericalInstabilityException(String message, long step) {
        this(message, step, null, null, Double.NaN, null);
    }

    public NumericalInstabilityException(String message, long step, String bodyId,
            String field, double simulationTimeSeconds, String value) {
        super(message);
        this.step = step;
        this.bodyId = bodyId;
        this.field = field;
        this.simulationTimeSeconds = simulationTimeSeconds;
        this.value = value;
    }

    public long getStep() {
        return step;
    }

    public String getBodyId() { return bodyId; }
    public String getField() { return field; }
    public double getSimulationTimeSeconds() { return simulationTimeSeconds; }
    public String getValue() { return value; }
}

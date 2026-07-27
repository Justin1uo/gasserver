package com.example.gas_server.data;

import java.util.Random;

/**
 * 数据模拟引擎
 * 根据时间阶段生成符合变化规律的气体检测模拟数据
 *
 * 时间阶段:
 * Phase 1 (0-30s):    CH4 ~2.8 波动, C2H6 ~0.09 波动
 * Phase 2 (30-60s):   CH4 3.5→10 上升, C2H6 0.12→0.20 上升
 * Phase 3 (60s+):     CH4 上升至20封顶, C2H6 上升至2.0封顶
 * Phase 4 (封顶后):   在封顶值附近波动
 */
public class DataSimulator {

    // 封顶值
    private static final double CH4_CAP = 20.0;
    private static final double C2H6_CAP = 2.0;

    // 时间阈值 (毫秒)
    private static final long PHASE1_END = 30_000L;
    private static final long PHASE2_END = 60_000L;

    // 波动系数
    private static final double VOLATILITY = 0.02;

    // 电量衰减速率: 每毫秒下降量 (100 / 60000ms ≈ 0.001667)
    private static final double BATTERY_DECAY_PER_MS = 1.0 / 60_000.0;

    private final Random random = new Random();
    private long startTime;
    private boolean running = false;

    // 当前值 (用于平滑波动)
    private double currentCh4 = 2.8;
    private double currentC2h6 = 0.09;
    private double currentTemp = 35.0;
    private double currentPress = 38.0;
    private double currentEnumb = 100.0;

    // 封顶标记
    private boolean ch4Capped = false;
    private boolean c2h6Capped = false;

    public void start() {
        startTime = System.currentTimeMillis();
        running = true;
        // 重置初始值
        currentCh4 = 2.8;
        currentC2h6 = 0.09;
        currentTemp = 35.0;
        currentPress = 38.0;
        currentEnumb = 100.0;
        ch4Capped = false;
        c2h6Capped = false;
    }

    public void stop() {
        running = false;
    }

    /**
     * 重置所有模拟数据到初始状态
     */
    public void reset() {
        running = false;
        currentCh4 = 2.8;
        currentC2h6 = 0.09;
        currentTemp = 35.0;
        currentPress = 38.0;
        currentEnumb = 100.0;
        ch4Capped = false;
        c2h6Capped = false;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * 生成下一个模拟数据点
     */
    public SimulatedData next() {
        if (!running) return null;

        long elapsed = System.currentTimeMillis() - startTime;

        // 更新各字段
        currentCh4 = simulateCh4(elapsed);
        currentC2h6 = simulateC2h6(elapsed);
        currentTemp = simulateTemp();
        currentPress = simulatePress();
        currentEnumb = simulateEnumb(elapsed);

        return new SimulatedData(currentCh4, currentC2h6, currentTemp, currentPress, currentEnumb);
    }

    /**
     * 甲烷模拟
     */
    private double simulateCh4(long elapsed) {
        double target;

        if (ch4Capped) {
            // 封顶后在 19~21 波动
            target = CH4_CAP;
        } else if (elapsed < PHASE1_END) {
            // Phase 1: 在 2.8 附近波动，不超过 3.5
            double progress = elapsed / (double) PHASE1_END;
            target = 2.5 + progress * 0.7; // 2.5 → 3.2
        } else if (elapsed < PHASE2_END) {
            // Phase 2: 3.5 → 10 线性上升
            double progress = (elapsed - PHASE1_END) / (double) (PHASE2_END - PHASE1_END);
            target = 3.5 + progress * 6.5;
        } else {
            // Phase 3: 10 → 20 上升
            double phase3Elapsed = elapsed - PHASE2_END;
            double phase3Duration = 60_000L; // 再用60秒升到封顶
            double progress = Math.min(phase3Elapsed / phase3Duration, 1.0);
            target = 10.0 + progress * 10.0;

            if (progress >= 1.0) {
                ch4Capped = true;
            }
        }

        // 添加平滑随机波动
        double result = applyVolatility(currentCh4, target);

        // 硬限制
        if (!ch4Capped && result > CH4_CAP) result = CH4_CAP;
        if (result < 0) result = 0;

        return result;
    }

    /**
     * 乙烷模拟
     */
    private double simulateC2h6(long elapsed) {
        double target;

        if (c2h6Capped) {
            target = C2H6_CAP;
        } else if (elapsed < PHASE1_END) {
            double progress = elapsed / (double) PHASE1_END;
            target = 0.06 + progress * 0.04; // 0.06 → 0.10
        } else if (elapsed < PHASE2_END) {
            double progress = (elapsed - PHASE1_END) / (double) (PHASE2_END - PHASE1_END);
            target = 0.12 + progress * 0.08;
        } else {
            double phase3Elapsed = elapsed - PHASE2_END;
            double phase3Duration = 60_000L;
            double progress = Math.min(phase3Elapsed / phase3Duration, 1.0);
            target = 0.20 + progress * 1.8;

            if (progress >= 1.0) {
                c2h6Capped = true;
            }
        }

        double result = applyVolatility(currentC2h6, target);
        if (!c2h6Capped && result > C2H6_CAP) result = C2H6_CAP;
        if (result < 0) result = 0;

        return result;
    }

    /**
     * 温度模拟: 35 ± 0.5
     */
    private double simulateTemp() {
        double target = 35.0;
        return applyVolatility(currentTemp, target, 0.005);
    }

    /**
     * 压力模拟: 38 ± 1.0
     */
    private double simulatePress() {
        double target = 38.0;
        return applyVolatility(currentPress, target, 0.008);
    }

    /**
     * 电量模拟: 每60秒下降1
     */
    private double simulateEnumb(long elapsed) {
        double target = 100.0 - (elapsed * BATTERY_DECAY_PER_MS);
        if (target < 0) target = 0;
        // 电量单调递减，允许微小波动
        double result = target + (random.nextGaussian() * 0.05);
        if (result > currentEnumb) result = currentEnumb; // 不允许上升
        if (result < 0) result = 0;
        return result;
    }

    /**
     * 带平滑约束的随机波动
     * 向目标值靠拢的同时添加高斯噪声
     */
    private double applyVolatility(double current, double target) {
        return applyVolatility(current, target, VOLATILITY);
    }

    private double applyVolatility(double current, double target, double volatility) {
        // 向目标值移动 80%，保留 20% 的当前值
        double base = current * 0.2 + target * 0.8;
        // 添加高斯噪声
        double noise = random.nextGaussian() * volatility * Math.abs(base);
        double result = base + noise;

        // 限制相邻跳变不超过 5%
        double maxDelta = Math.abs(current) * 0.05;
        if (Math.abs(result - current) > maxDelta) {
            result = current + Math.signum(result - current) * maxDelta;
        }

        return result;
    }

    /**
     * 获取当前运行时长(毫秒)
     */
    public long getElapsedMs() {
        if (!running) return 0;
        return System.currentTimeMillis() - startTime;
    }
}

package com.example.gas_server.data;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 模拟气体检测数据模型，对应发送的 JSON 格式
 */
public class SimulatedData {

    // 动态模拟字段
    private double ch4Conc;    // 甲烷浓度
    private double c2h6Conc;   // 乙烷浓度
    private double temp;       // 温度
    private double press;      // 压力
    private double enumb;      // 电量

    // 固定字段
    private static final String NAME = "realtime";
    private static final double CH4_2F = 44.445282;
    private static final double CH4_K = 0.077631;
    private static final double C2H6_2F = 15.514145;
    private static final double TEC1_TEMP = 35.014999;
    private static final double TEC2_TEMP = 35.018002;
    private static final double FLOW = 0.000000;
    private static final double PUMP1 = 3000.000000;
    private static final double PUMP2 = 2500.000000;

    public SimulatedData(double ch4Conc, double c2h6Conc, double temp, double press, double enumb) {
        this.ch4Conc = ch4Conc;
        this.c2h6Conc = c2h6Conc;
        this.temp = temp;
        this.press = press;
        this.enumb = enumb;
    }

    /**
     * 将数据转为 JSON 字符串
     */
    public String toJson() {
        try {
            JSONObject json = new JSONObject();
            json.put("NAME", NAME);
            json.put("CH4_CONC", round6(ch4Conc));
            json.put("CH4_2f", CH4_2F);
            json.put("CH4_K", CH4_K);
            json.put("C2H6_CONC", round6(c2h6Conc));
            json.put("C2H6_2f", C2H6_2F);
            json.put("C2H6_K", JSONObject.NULL);
            json.put("temp", round6(temp));
            json.put("tec1temp", TEC1_TEMP);
            json.put("tec2temp", TEC2_TEMP);
            json.put("press", round6(press));
            json.put("flow", FLOW);
            json.put("pump1", PUMP1);
            json.put("pump2", PUMP2);
            json.put("enumb", round2(enumb));
            return json.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    /**
     * 格式化显示用的 JSON（带缩进）
     */
    public String toPrettyJson() {
        try {
            JSONObject json = new JSONObject();
            json.put("NAME", NAME);
            json.put("CH4_CONC", round6(ch4Conc));
            json.put("CH4_2f", CH4_2F);
            json.put("CH4_K", CH4_K);
            json.put("C2H6_CONC", round6(c2h6Conc));
            json.put("C2H6_2f", C2H6_2F);
            json.put("C2H6_K", JSONObject.NULL);
            json.put("temp", round6(temp));
            json.put("tec1temp", TEC1_TEMP);
            json.put("tec2temp", TEC2_TEMP);
            json.put("press", round6(press));
            json.put("flow", FLOW);
            json.put("pump1", PUMP1);
            json.put("pump2", PUMP2);
            json.put("enumb", round2(enumb));
            return json.toString(2);
        } catch (JSONException e) {
            return "{}";
        }
    }

    private double round6(double value) {
        return Math.round(value * 1000000.0) / 1000000.0;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    // Getters
    public double getCh4Conc() { return ch4Conc; }
    public double getC2h6Conc() { return c2h6Conc; }
    public double getTemp() { return temp; }
    public double getPress() { return press; }
    public double getEnumb() { return enumb; }
}

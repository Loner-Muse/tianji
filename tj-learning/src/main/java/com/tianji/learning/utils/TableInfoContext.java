package com.tianji.learning.utils;

/**
 * 用于在ThreadLocal中传递动态表名
 * 定时任务计算好表名后存入，MyBatis-Plus动态表名插件从中读取
 */
public class TableInfoContext {

    private static final ThreadLocal<String> TL = new ThreadLocal<>();

    public static void setInfo(String info) {
        TL.set(info);
    }

    public static String getInfo() {
        return TL.get();
    }

    public static void remove() {
        TL.remove();
    }
}

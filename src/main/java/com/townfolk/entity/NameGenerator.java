package com.townfolk.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.util.RandomSource;

/** 居民的名字：姓 + 名，名按性别区分。 */
public final class NameGenerator {
    private static final String[] SURNAMES = {
            "王", "李", "张", "刘", "陈", "杨", "赵", "黄", "周", "吴", "徐", "孙", "胡", "朱", "高", "林",
            "何", "郭", "马", "罗", "梁", "宋", "郑", "谢", "韩", "唐", "冯", "于", "董", "萧", "程", "曹",
            "袁", "邓", "许", "傅", "沈", "曾", "彭", "吕"
    };
    private static final String[] MALE_NAMES = {
            "大山", "铁柱", "石头", "有田", "长庚", "文博", "守义", "德福", "永年", "承志", "明远", "安平",
            "立冬", "春生", "二牛", "青松", "向阳", "怀仁", "广田", "满仓", "子谦", "景行", "木生", "河清",
            "世安", "金宝", "来福", "栓子", "岩", "松", "柏", "川", "耕", "樵", "禾", "诚"
    };
    private static final String[] FEMALE_NAMES = {
            "秀兰", "小满", "春梅", "桂香", "玉珍", "巧云", "月娥", "采薇", "素心", "杏儿", "翠花", "婉清",
            "谷雨", "青禾", "若兰", "小荷", "阿宁", "锦书", "秋菊", "雪梅", "芸娘", "蕙兰", "朝云", "念慈",
            "静姝", "阿桃", "红豆", "麦穗", "兰", "菱", "芷", "萱", "禾", "桑", "棠", "絮"
    };

    /** 取 count 个互不相同的姓，保证开局居民之间没有亲属关系。 */
    public static List<String> distinctSurnames(RandomSource random, int count) {
        List<String> pool = new ArrayList<>(List.of(SURNAMES));
        Collections.shuffle(pool, new java.util.Random(random.nextLong()));
        return pool.subList(0, Math.min(count, pool.size()));
    }

    public static String givenName(RandomSource random, boolean male) {
        String[] pool = male ? MALE_NAMES : FEMALE_NAMES;
        return pool[random.nextInt(pool.length)];
    }

    private NameGenerator() {}
}

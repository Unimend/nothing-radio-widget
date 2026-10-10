package com.unimend.nothingradio;

/**
 * 电台数据类：名称 + 流地址。
 *
 * 流地址来自央广 CNR 官方 HLS（.m3u8），由 <a href="https://github.com/SLCA2020/gao/blob/master/radio.m3u">SLCA2020/gao/radio.m3u</a>
 * 汇总。HLS 即 HTTP Live Streaming，把音频切成分片、用 .m3u8 播放列表索引，
 * Android 的 MediaPlayer 从 4.0 起原生支持。
 *
 * 想增删电台：直接改下面的 LIST 数组即可，无需改其它代码。
 */
public class Station {
    public final String name;
    public final String url;

    public Station(String name, String url) {
        this.name = name;
        this.url = url;
    }

    public static final Station[] LIST = new Station[]{
            // CNR 央广（ngcdn001/002 节点可用）
            new Station("中国之声", "https://ngcdn001.cnr.cn/live/zgzs/index.m3u8"),
            new Station("经济之声", "https://ngcdn002.cnr.cn/live/jjzs/index.m3u8"),
            // CRI 中国国际广播电台（sk.cri.cn，节点可用）
            new Station("环球资讯", "https://sk.cri.cn/905.m3u8"),
            new Station("中文环球", "https://sk.cri.cn/hyhq.m3u8"),
            new Station("南海之声", "https://sk.cri.cn/nhzs.m3u8"),
            new Station("海峡飞虹", "https://sk.cri.cn/hxfh.m3u8"),
            // 省级电台（satellitepull.cnr.cn，卫星拉流，可用）
            new Station("浙江之声", "https://satellitepull.cnr.cn/live/wxzjzs/playlist.m3u8"),
            new Station("浙江交通之声", "https://satellitepull.cnr.cn/live/wxzjjtgb/playlist.m3u8"),
            new Station("江苏新闻广播", "https://satellitepull.cnr.cn/live/wx32jsxwgb/playlist.m3u8"),
            new Station("江苏经典音乐", "https://satellitepull.cnr.cn/live/wx32jsjdlxyy/playlist.m3u8"),
            new Station("北京新闻广播", "https://satellitepull.cnr.cn/live/wxbjxwgb/playlist.m3u8"),
            new Station("广东音乐之声", "https://satellitepull.cnr.cn/live/wxgdyyzs/playlist.m3u8"),
            new Station("深圳飞扬971", "https://satellitepull.cnr.cn/live/wxszfy971/playlist.m3u8"),
            new Station("广西音乐广播", "https://satellitepull.cnr.cn/live/wx32gxwygb/playlist.m3u8"),
            new Station("重庆音乐广播", "https://satellitepull.cnr.cn/live/wxcqyygb/playlist.m3u8"),
            new Station("陕西新闻广播", "https://satellitepull.cnr.cn/live/wxsxxxwgb/playlist.m3u8"),
            new Station("福建新闻广播", "https://satellitepull.cnr.cn/live/wx32fjxwgb/playlist.m3u8"),
            new Station("河北综合广播", "https://satellitepull.cnr.cn/live/wxhebzhgb/playlist.m3u8"),
    };
}

package io.github.spring_ibmmq;

import java.util.Hashtable;

/** 接続直前に IBM MQ native 接続プロパティを調整する拡張点。 */
@FunctionalInterface
public interface IbmmqConnectionPropertiesCustomizer {
    /**
     * 接続に使用するプロパティを変更する。
     *
     * @param properties 標準の接続設定を反映した可変のプロパティ
     */
    void customize(Hashtable<String, Object> properties);
}

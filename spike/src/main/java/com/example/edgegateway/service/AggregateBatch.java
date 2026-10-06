package com.example.edgegateway.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一批正在攒的**承运单**（#142）：合并动作的护栏。
 *
 * <p>一条消息走到合并动作时，把「我是谁、我这一片是什么、我的上下文到哪儿了」记成一张
 * {@link Carrier}（承运单），攒在 {@code (运行 + 批号)} 这个键下；攒齐之后一次发出去，
 * 再按承运单把响应扇回每一条（ADR-0017 决策 6/7）。</p>
 *
 * <p><b>为什么不是 Camel 的 {@code aggregate} EIP</b>：实测它的「攒齐之后那一段」跑在**别的线程**上
 * （探针：{@code sendBody} 返回时一次都没发，等一会儿才发）。放进这条链就是要命的——读库那批的
 * **水位推进**与**失败判定**都在 split 之后同步收尾，攒齐一旦异步化就会出现「水位推了、请求还没发」
 * 与「合并 5xx 了、这一批却算成功」。所以护栏自己拿着、在**到达的那条消息的线程里**同步判齐。</p>
 *
 * <p><b>齐不齐怎么判</b>：每条消息带着「它那一行本该摊出几条」（读库那批是 1 条一行；拆分动作把 1 变成
 * k，就按 k 记）。{@code expected} 是**按行去重后**的合计（同一行拆出来的 k 条带的是同一个父消息标识，
 * 去重就靠它），到齐判据是「收到的条数 = 本该有的条数」。凑不满**什么都不发**——绝不静默发半批
 * （ADR-0017 决策 8）。</p>
 *
 * <p>纯逻辑（不碰 Camel、不碰库），所以能脱离整链单测（见 {@code AggregateBatchTest}）。</p>
 */
final class AggregateBatch {

    /**
     * 一张承运单：一条消息在合并那一步的**全部现场**。
     *
     * @param body      这条消息自己那一片（进请求体数组的就是它；配对结果按 {@code attachAs} 落在它上面）
     * @param context   走到这里为止的上下文快照（扇回之后后续动作接着用）
     * @param rowKey    它来自读取批的哪一行（父消息标识优先，否则是消息标识）——判齐时按它去重
     * @param rowShares 它那一行本该摊出几条（不经过拆分就是 1）
     * @param skipped   这一步在断点续投里被跳过（不参与发送，只把现场还回去让后续步骤照跑）
     */
    record Carrier(String messageId, String businessKey, Integer batchSeq, String parentMessageId,
                   Object body, Map<Integer, Object> context, String rowKey, int rowShares, boolean skipped) {

        /**
         * 配上响应之后那张单：{@code attachAs} 落在自己的 body 上、攒批序号定下来、上下文添上这一步的输出
         * （下游用 {@code sourceStep=合并动作序号} 取到的就是自己这一份）。
         */
        Carrier settled(int seq, String attachAs, Object slice, int actionIndex) {
            Map<Integer, Object> next = new LinkedHashMap<>(context);
            next.put(actionIndex, slice);
            return new Carrier(messageId, businessKey, seq, parentMessageId,
                    attach(body, attachAs, slice), next, rowKey, rowShares, skipped);
        }
    }

    private final List<Carrier> carriers = new ArrayList<>();
    private final Map<String, Integer> sharesByRow = new LinkedHashMap<>();

    void add(Carrier carrier) {
        carriers.add(carrier);
        sharesByRow.merge(carrier.rowKey(), carrier.rowShares(), Math::max);
    }

    /** 到齐了没有：**收到了本该有的那么多条**。一条都没有（这一批没产出消息）不算齐。 */
    boolean complete() {
        return !carriers.isEmpty() && carriers.size() >= expected();
    }

    /** 这一批本该到的条数（按行去重后的合计）。 */
    int expected() {
        return sharesByRow.values().stream().mapToInt(Integer::intValue).sum();
    }

    int arrived() {
        return carriers.size();
    }

    List<Carrier> carriers() {
        return List.copyOf(carriers);
    }

    /** 整批都被断点续投跳过（那就不发请求，只把现场还回去）。 */
    boolean allSkipped() {
        return !carriers.isEmpty() && carriers.stream().allMatch(Carrier::skipped);
    }

    /**
     * 响应里那一串「每一项对应一条消息」的东西。
     *
     * <p>三种常见形状都认：本身是数组；是对象但只有一个数组字段（{@code {"codes":[ … ]}}，与请求体对称）；
     * 其余（单个对象 / 标量）当**只有一项**处理。形状认不出来时抛错——不猜、也不静默当空。</p>
     */
    @SuppressWarnings("unchecked")
    static List<Object> responseItems(Object response) {
        if (response instanceof List<?> list) {
            return (List<Object>) list;
        }
        if (response instanceof Map<?, ?> map) {
            List<List<?>> lists = new ArrayList<>();
            for (Object value : map.values()) {
                if (value instanceof List<?> list) {
                    lists.add(list);
                }
            }
            if (lists.size() == 1) {
                return (List<Object>) lists.getFirst();
            }
            return List.of((Object) map);
        }
        if (response == null) {
            throw new IllegalStateException("合并的响应是空的，没法按配对字段扇回");
        }
        return List.of(response);
    }

    /**
     * 按 {@code resultKeyField} 把响应配回每条消息：键相等就用 {@code String.valueOf} 比——
     * 目标端回的 {@code 88.1} 这种编号在 JSON 里可能是数字，业务键那一侧恒是字符串。
     *
     * @return 消息标识 → 它配对到的那一份；**没配上的不在表里**（由调用方决定怎么说这句话）
     */
    static Map<String, Object> pair(Object response, String resultKeyField, List<Carrier> carriers) {
        Map<String, Object> byKey = new LinkedHashMap<>();
        for (Object item : responseItems(response)) {
            Object key = ElementMessageFactory.valueAt(item, resultKeyField);
            if (key != null) {
                byKey.putIfAbsent(String.valueOf(key), item);
            }
        }
        Map<String, Object> paired = new LinkedHashMap<>();
        for (Carrier carrier : carriers) {
            if (carrier.businessKey() == null) {
                continue;
            }
            Object item = byKey.get(carrier.businessKey());
            if (item != null) {
                paired.put(carrier.messageId(), item);
            }
        }
        return paired;
    }

    /**
     * 配对结果落进这条消息的 body（{@code attachAs} 那一格）。
     *
     * <p>body 不是对象时**显式失败**：硬包一层 {@code {"<attachAs>": …}} 会把这条消息原有的字段全丢掉，
     * 那是无中生有（与 {@code ElementMessageFactory.inherit} 同一条取舍）。</p>
     */
    static Object attach(Object body, String attachAs, Object slice) {
        if (!(body instanceof Map<?, ?> map)) {
            throw new IllegalStateException("合并的配对结果要落进 body 的 " + attachAs
                    + " 键，但这条消息的 body 不是对象（是 "
                    + (body == null ? "空" : body.getClass().getSimpleName()) + "）");
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        map.forEach((key, value) -> merged.put(String.valueOf(key), value));
        merged.put(attachAs, slice);
        return merged;
    }
}

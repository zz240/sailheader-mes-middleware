-- 一分多动作（ONE_TO_MANY）上线时把库里动作行归一到新种类：type 改成本种类，addition 补一格 mode。
--
-- 为什么用数据迁移而不是读取时兼容：job_action.type 是枚举列（MyBatis 按枚举名映射），旧名字在新代码里
-- 没有对应的常量；读取时兼容就得把旧常量留在枚举里（等于没归一）。只动 type 与 addition.mode 两处，
-- **不动**各自那几格配置键（拆分档 elementKey/keepParent、还原档 resultKeyField/attachAs，见
-- OneToManyConfig.from）——落库的 JSON 因此与原来一一对应，只多一格 mode。
--
-- 幂等：改完 type 就不再命中 where，重跑是 no-op；addition 为空时用 JSON_OBJECT() 兜住。
UPDATE job_action
SET type = 'ONE_TO_MANY',
    addition = JSON_SET(COALESCE(addition, JSON_OBJECT()), '$.mode', 'split')
WHERE type = 'SPLIT';

UPDATE job_action
SET type = 'ONE_TO_MANY',
    addition = JSON_SET(COALESCE(addition, JSON_OBJECT()), '$.mode', 'restore')
WHERE type = 'FANOUT';
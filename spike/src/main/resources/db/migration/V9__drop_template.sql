-- V9__drop_template.sql
-- 删除模板功能（#153）：template 表只被模板控制器与服务使用，没有任何地方
-- 把 config_json 变成任务、动作链或数据源；整条功能删除后表不再被代码使用。
--
-- V1__init_schema.sql 是历史迁移，不能回头改，所以在这里补一次性的删表。
-- 幂等：表不存在时跳过，重跑是 no-op。

DROP TABLE IF EXISTS template;

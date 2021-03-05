-- 中间件数据库
-- Version 1.0.1

-- drop database if exists middleware;
-- create database middleware;
-- \c middleware;

-- --------------------------------------------------
-- 执行任务
-- --------------------------------------------------
drop table if exists job;
create table job
    (
        id          bigserial primary key,
        name        varchar unique,
        crontab     varchar,
        remarks     varchar,
        status      smallint  default 2,
        created_by  integer,
        created_on  timestamp default now(),
        modified_by integer,
        modified_on timestamp
    );
comment
    on table job is '执行任务表';
comment
    on column job.id is 'id';
comment
    on column job.name is '任务名称';
comment
    on column job.crontab is '定时表达式';
comment
    on column job.remarks is '备注';
comment
    on column job.status is '状态 1:启用,2:未启用,-1:删除';
comment
    on column job.created_on is '创建时间';
comment
    on column job.created_by is '创建人id';
comment
    on column job.modified_on is '更改时间';
comment
    on column job.modified_by is '更改人id';


-- --------------------------------------------------
-- 执行任务表索引
-- --------------------------------------------------
CREATE
    INDEX idx_job_name ON job (name);
CREATE
    INDEX idx_job_status ON job (status);


-- --------------------------------------------------
-- 动作流表
-- --------------------------------------------------
drop table if exists action;
create table action
    (
        id                bigserial primary key,
        job_id            bigint,
        name              varchar,
        type              smallint,
        status            smallint  default 1,
        index             integer,
        consume_data_type integer default 1,
        addition          json,
        created_by        integer,
        created_on        timestamp default now(),
        modified_by       integer,
        modified_on       timestamp
    );
comment
    on table action is '动作流表';
comment
    on column action.id is 'id';
comment
    on column action.job_id is '绑定的任务Id';
comment
    on column action.name is '动作名称';
comment
    on column action.type is '动作类型';
comment
    on column action.status is '状态,1:启用,-1:删除';
comment
    on column action.index is '动作序号';
comment
    on column action.consume_data_type is '消费数据方式';
comment
    on column action.addition is '附加辅助信息';
comment
    on column action.created_on is '创建时间';
comment
    on column action.created_by is '创建人id';
comment
    on column action.modified_on is '更改时间';
comment
    on column action.modified_by is '更改人id';


-- --------------------------------------------------
-- 动作表索引
-- --------------------------------------------------
CREATE
    INDEX idx_action_id ON action (job_id);
CREATE
    INDEX idx_action_name ON action (name);
CREATE
    INDEX idx_action_type ON action (type);
CREATE
    INDEX idx_action_index ON action (index);
CREATE
    INDEX idx_action_consume_data_type ON action (consume_data_type);


-- --------------------------------------------------
-- 数据源表
-- --------------------------------------------------
drop table if exists data_source;
create table data_source
    (
        id          bigserial primary key,
        name        varchar,
        type        smallint,
        status      smallint  default 1,
        address     varchar,
        addition    json,
        created_by  integer,
        created_on  timestamp default now(),
        modified_by integer,
        modified_on timestamp
    );
comment
    on table data_source is '数据源';
comment
    on column data_source.id is 'id';
comment
    on column data_source.name is '数据源名称';
comment
    on column data_source.type is '数据源类型';
comment
    on column data_source.status is '状态,1:启用,-1:删除';
comment
    on column data_source.address is '数据源地址';
comment
    on column data_source.addition is '附加辅助信息';
comment
    on column data_source.created_on is '创建时间';
comment
    on column data_source.created_by is '创建人id';
comment
    on column data_source.modified_on is '更改时间';
comment
    on column data_source.modified_by is '更改人id';


-- --------------------------------------------------
-- 数据源表索引
-- --------------------------------------------------
CREATE
    INDEX idx_data_source_name ON data_source (name);
CREATE
    INDEX idx_data_source_type ON data_source (type);
CREATE
    INDEX idx_data_source_address ON data_source (address);
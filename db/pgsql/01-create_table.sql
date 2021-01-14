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
        status      smallint,
        created_by  integer,
        created_on  timestamp,
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
-- 执行任务日志表
-- --------------------------------------------------
drop table if exists job_log;
create table job_log
    (
        id          bigserial primary key,
        job_id      bigint,
        operation   smallint,
        job_content text,
        modified_by integer,
        modified_on timestamp
    );
comment
    on table job_log is '执行任务日志表';
comment
    on column job_log.id is 'id';
comment
    on column job_log.job_id is '执行任务id';
comment
    on column job_log.operation is '操作数,1:insert,2:update,3:delete';
comment
    on column job_log.job_content is '更改内容';
comment
    on column job_log.modified_by is '更改人';
comment
    on column job_log.modified_on is '更改时间';


-- --------------------------------------------------
-- 动作流表
-- --------------------------------------------------
drop table if exists action_stream;
create table action_stream
    (
        id          bigserial primary key,
        job_id      bigint,
        name        varchar,
        type        smallint,
        status      smallint,
        index       integer,
        temp_data   json,
        addition    json,
        created_by  integer,
        created_on  timestamp,
        modified_by integer,
        modified_on timestamp
    );
comment
    on table action_stream is '动作流表';
comment
    on column action_stream.id is 'id';
comment
    on column action_stream.job_id is '绑定的任务Id';
comment
    on column action_stream.name is '动作名称';
comment
    on column action_stream.type is '动作类型';
comment
    on column action_stream.status is '状态,1:启用,-1:删除';
comment
    on column action_stream.index is '动作序号';
comment
    on column action_stream.temp_data is '输出数据';
comment
    on column action_stream.addition is '附加辅助信息';
comment
    on column action_stream.created_on is '创建时间';
comment
    on column action_stream.created_by is '创建人id';
comment
    on column action_stream.modified_on is '更改时间';
comment
    on column action_stream.modified_by is '更改人id';


-- --------------------------------------------------
-- 动作表索引
-- --------------------------------------------------
CREATE
    INDEX idx_action_stream_id ON action_stream (job_id);
CREATE
    INDEX idx_action_stream_name ON action_stream (name);
CREATE
    INDEX idx_action_stream_type ON action_stream (type);
CREATE
    INDEX idx_action_stream_index ON action_stream (index);

-- --------------------------------------------------
-- 动作日志表
-- --------------------------------------------------
drop table if exists action_stream_log;
create table action_stream_log
    (
        id               bigserial primary key,
        action_stream_id bigint,
        operation        smallint,
        content          text,
        modified_by      integer,
        modified_on      timestamp
    );
comment
    on table action_stream_log is '执行任务日志表';
comment
    on column action_stream_log.id is 'id';
comment
    on column action_stream_log.action_stream_id is '执行任务id';
comment
    on column action_stream_log.operation is '操作数,1:insert,2:update,3:delete';
comment
    on column action_stream_log.content is '更改内容';
comment
    on column action_stream_log.modified_by is '更改人';
comment
    on column action_stream_log.modified_on is '更改时间';


-- --------------------------------------------------
-- 数据源表
-- --------------------------------------------------
drop table if exists data_source;
create table data_source
    (
        id          bigserial primary key,
        name        varchar,
        type        smallint,
        status      smallint,
        address     varchar,
        addition    json,
        created_by  integer,
        created_on  timestamp,
        modified_by integer,
        modified_on timestamp
    );
comment
    on table data_source is '动作流表';
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


-- --------------------------------------------------
-- 数据源日志表
-- --------------------------------------------------
drop table if exists data_source_log;
create table data_source_log
    (
        id             bigserial primary key,
        data_source_id bigint,
        operation      smallint,
        content        text,
        modified_by    integer,
        modified_on    timestamp
    );
comment
    on table data_source_log is '执行任务日志表';
comment
    on column data_source_log.id is 'id';
comment
    on column data_source_log.data_source_id is '数据源id';
comment
    on column data_source_log.operation is '操作数,1:insert,2:update,3:delete';
comment
    on column data_source_log.content is '更改内容';
comment
    on column data_source_log.modified_by is '更改人';
comment
    on column data_source_log.modified_on is '更改时间';


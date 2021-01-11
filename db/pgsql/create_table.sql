--
drop table if exists import_job;
create table import_job
(
  id bigserial primary key,
  rest_url varchar,
  name varchar unique ,
  cron_expression varchar,
  target_address varchar,
  remarks varchar,
  enabled boolean DEFAULT true,
  status varchar DEFAULT 'ACTIVE',
  create_date timestamp,
  last_sync_date timestamp,
  last_sync_status varchar
);
alter table import_job owner to postgres;
comment on table import_job is '定时同步任务表';
comment on column import_job.id is 'id';
comment on column import_job.rest_url is '源地址';
comment on column import_job.name is '产品名称';
comment on column import_job.cron_expression is 'cron表达式';
comment on column import_job.target_address is '目标地址';
comment on column import_job.remarks is '备注';
comment on column import_job.enabled is '是否开启';
comment on column import_job.status is '状态';
comment on column import_job.create_date is '创建时间';
comment on column import_job.last_sync_date is '最后修改时间';
comment on column import_job.last_sync_status is '最后同步状态';

--

drop table if exists enterprise_route_mapping;
create table enterprise_route_mapping
(
  id bigserial primary key,
  code varchar unique,
  enterprise_name varchar,
  target_address varchar
);
alter table enterprise_route_mapping owner to postgres;
comment on table enterprise_route_mapping is '企业路由映射表';
comment on column enterprise_route_mapping.id is 'id';
comment on column enterprise_route_mapping.code is '企业编码';
comment on column enterprise_route_mapping.enterprise_name is '公司名称';
comment on column enterprise_route_mapping.target_address is '映射地址';





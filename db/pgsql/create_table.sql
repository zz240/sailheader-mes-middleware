drop table if exists cf_action;
create table cf_action
(
  id bigserial primary key,
  code varchar,
  name varchar,
  sequence bigint,
  enabled boolean DEFAULT TRUE,
  pinyin varchar,
  en_us_name varchar,
  zh_cn_name varchar,
  en_us_content varchar,
  zh_cn_content varchar,
  status varchar DEFAULT 'ACTIVE',
  orientdb_id varchar,
  created_by bigint,
  created_name_by varchar,
  created_date timestamp,
  last_modified_by bigint,
  last_modified_name_by varchar,
  last_modified_date timestamp
);
alter table cf_action owner to postgres;
comment on table cf_action is '动作配置';
comment on column cf_action.id is 'id';
comment on column cf_action.code is '编码';
comment on column cf_action.name is '名称';
comment on column cf_action.sequence is '序号';
comment on column cf_action.enabled is '是否启用';
comment on column cf_action.pinyin is '拼音';
comment on column cf_action.en_us_name is '英文名';
comment on column cf_action.zh_cn_name is '中文名';
comment on column cf_action.en_us_content is '英文内容';
comment on column cf_action.zh_cn_content is '中文内容';
comment on column cf_action.status is '状态';
comment on column cf_action.orientdb_id is 'orientDb中id';
comment on column cf_action.created_by is '创建人id';
comment on column cf_action.created_name_by is '创建人姓名';
comment on column cf_action.created_date is '创建时间';
comment on column cf_action.last_modified_by is '最后修改人id';
comment on column cf_action.last_modified_name_by is '最后修改人姓名';
comment on column cf_action.last_modified_date is '最后修改时间';

CREATE  INDEX cf_action_code on cf_action (code);


drop table if exists common_data;
create table common_data
(
  id bigserial primary key,
  code varchar,
  validation boolean,
  enabled boolean DEFAULT TRUE,
  success_message_code varchar,
  fail_message_code varchar,
  validation_field varchar,
  value varchar,
  regx varchar,
  rule_code varchar,
  validation_result boolean,
  option varchar,
  sequence varchar,
  url varchar,
  http_method varchar,
  http_body_class_name varchar,
  http_body_json_string varchar,
  result_field varchar,
  internal boolean,
  return_class_name varchar,
  multiple boolean,
  pinyin varchar,
  en_us_name varchar,
  zh_cn_name varchar,
  en_us_content varchar,
  zh_cn_content varchar,
  status varchar DEFAULT 'ACTIVE',
  orientdb_id varchar,
  created_by bigint,
  created_name_by varchar,
  created_date timestamp,
  last_modified_by bigint,
  last_modified_name_by varchar,
  last_modified_date timestamp
);
alter table common_data owner to postgres;
comment on table common_data is '公用数据表';
comment on column common_data.id is 'id';
comment on column common_data.code is '编码，用来定位这个数据模型';
comment on column common_data.validation is '用来确定是否验证';
comment on column common_data.enabled is '是否启用';
comment on column common_data.success_message_code is '成功消息编码';
comment on column common_data.fail_message_code is '失败消息编码';
comment on column common_data.validation_field is '须验证CommonData字段（可为空）';
comment on column common_data.value is '自定义比对值';
comment on column common_data.regx is '正则表达式';
comment on column common_data.rule_code is '验证规则编码';
comment on column common_data.validation_result is '验证结果';
comment on column common_data.option is '自定义参数';
comment on column common_data.sequence is '执行顺序';
comment on column common_data.url is '数据源链接，可通过标准功能微服务/客制化微服务提供的服务URL获取数据';
comment on column common_data.http_method is 'GET，POST，PUT，DELETE';
comment on column common_data.http_body_class_name is 'httpBody类名';
comment on column common_data.http_body_json_string is 'httpBody JSON 字符串';
comment on column common_data.result_field is '返回结果的某字段：当为空时，查找此类value字段；不为空时，取数据源返回对象的此字段值/List<此字段类型>';
comment on column common_data.internal is '是内部或外部服务';
comment on column common_data.return_class_name is '返回数据完整类名，如为空，则默认为JSONObject';
comment on column common_data.multiple is 'true：返回List，false：返回单个对象';
comment on column common_data.pinyin is '拼音';
comment on column common_data.en_us_name is '英文名';
comment on column common_data.zh_cn_name is '中文名';
comment on column common_data.en_us_content is '英文内容';
comment on column common_data.zh_cn_content is '中文内容';
comment on column common_data.status is '状态';
comment on column common_data.orientdb_id is 'orientDb中id';
comment on column common_data.created_by is '创建人id';
comment on column common_data.created_name_by is '创建人姓名';
comment on column common_data.created_date is '创建时间';
comment on column common_data.last_modified_by is '最后修改人id';
comment on column common_data.last_modified_name_by is '最后修改人姓名';
comment on column common_data.last_modified_date is '最后修改时间';

CREATE  INDEX common_data_code on common_data (code);


drop table if exists common_data_base;
create table common_data_base
(
  id bigserial primary key,
  sequence varchar,
  url varchar,
  http_method varchar,
  http_body_class_name varchar,
  http_body_json_string varchar,
  result_field varchar,
  internal boolean,
  return_class_name varchar,
  multiple boolean,
  pinyin varchar,
  en_us_name varchar,
  zh_cn_name varchar,
  en_us_content varchar,
  zh_cn_content varchar,
  status varchar DEFAULT 'ACTIVE',
  orientdb_id varchar,
  created_by bigint,
  created_name_by varchar,
  created_date timestamp,
  last_modified_by bigint,
  last_modified_name_by varchar,
  last_modified_date timestamp
);
alter table common_data_base owner to postgres;
comment on table common_data_base is '公用数据基础表';
comment on column common_data_base.id is 'id';
comment on column common_data_base.sequence is '序号';
comment on column common_data_base.url is '数据源链接，可通过标准功能微服务/客制化微服务提供的服务URL获取数据';
comment on column common_data_base.http_method is 'GET，POST，PUT，DELETE';
comment on column common_data_base.http_body_class_name is 'httpBody 类名';
comment on column common_data_base.http_body_json_string is 'httpBody JSON 字符串';
comment on column common_data_base.result_field is '返回结果的某字段：当为空时，查找此类value字段；不为空时，取数据源返回对象的此字段值/List<此字段类型>';
comment on column common_data_base.internal is '是内部或外部服务';
comment on column common_data_base.return_class_name is '返回数据完整类名，如为空，则默认为JSONObject';
comment on column common_data_base.multiple is 'true：返回List，false：返回单个对象';
comment on column common_data_base.pinyin is '拼音';
comment on column common_data_base.en_us_name is '英文名';
comment on column common_data_base.zh_cn_name is '中文名';
comment on column common_data_base.en_us_content is '英文内容';
comment on column common_data_base.zh_cn_content is '中文内容';
comment on column common_data_base.status is '状态';
comment on column common_data_base.orientdb_id is 'orientDb中id';
comment on column common_data_base.created_by is '创建人id';
comment on column common_data_base.created_name_by is '创建人姓名';
comment on column common_data_base.created_date is '创建时间';
comment on column common_data_base.last_modified_by is '最后修改人id';
comment on column common_data_base.last_modified_name_by is '最后修改人姓名';
comment on column common_data_base.last_modified_date is '最后修改时间';



drop table if exists common_data_validation;
create table common_data_validation
(
  id bigserial primary key,
  code varchar,
  validation_field varchar,
  value varchar,
  regx varchar,
  rule_code varchar,
  validation_result boolean,
  sequence varchar,
  url varchar,
  http_method varchar,
  http_body_class_name varchar,
  http_body_json_string varchar,
  result_field varchar,
  internal boolean,
  return_class_name varchar,
  multiple boolean,
  pinyin varchar,
  en_us_name varchar,
  zh_cn_name varchar,
  en_us_content varchar,
  zh_cn_content varchar,
  status varchar DEFAULT 'ACTIVE',
  orientdb_id varchar,
  created_by bigint,
  created_name_by varchar,
  created_date timestamp,
  last_modified_by bigint,
  last_modified_name_by varchar,
  last_modified_date timestamp
);
alter table common_data_validation owner to postgres;
comment on table common_data_validation is '公用数据验证表';
comment on column common_data_validation.id is 'id';
comment on column common_data_validation.code is '编码';
comment on column common_data_validation.validation_field is '须验证CommonData字段（可为空）';
comment on column common_data_validation.value is '自定义比对值';
comment on column common_data_validation.regx is '正则表达式';
comment on column common_data_validation.rule_code is '验证规则编码';
comment on column common_data_validation.validation_result is '验证结果';
comment on column common_data_validation.sequence is '序号';
comment on column common_data_validation.url is '数据源链接，可通过标准功能微服务/客制化微服务提供的服务URL获取数据';
comment on column common_data_validation.http_method is 'GET，POST，PUT，DELETE';
comment on column common_data_validation.http_body_class_name is 'httpBody 类名';
comment on column common_data_validation.http_body_json_string is 'httpBody JSON 字符串';
comment on column common_data_validation.result_field is '返回结果的某字段：当为空时，查找此类value字段；不为空时，取数据源返回对象的此字段值/List<此字段类型>';
comment on column common_data_validation.internal is '是内部或外部服务';
comment on column common_data_validation.return_class_name is '返回数据完整类名，如为空，则默认为JSONObject';
comment on column common_data_validation.multiple is 'true：返回List，false：返回单个对象';
comment on column common_data_validation.pinyin is '拼音';
comment on column common_data_validation.en_us_name is '英文名';
comment on column common_data_validation.zh_cn_name is '中文名';
comment on column common_data_validation.en_us_content is '英文内容';
comment on column common_data_validation.zh_cn_content is '中文内容';
comment on column common_data_validation.status is '状态';
comment on column common_data_validation.orientdb_id is 'orientDb中id';
comment on column common_data_validation.created_by is '创建人id';
comment on column common_data_validation.created_name_by is '创建人姓名';
comment on column common_data_validation.created_date is '创建时间';
comment on column common_data_validation.last_modified_by is '最后修改人id';
comment on column common_data_validation.last_modified_name_by is '最后修改人姓名';
comment on column common_data_validation.last_modified_date is '最后修改时间';



drop table if exists mes_entity_relation;
create table mes_entity_relation
(
  id bigserial primary key,
  from_id varchar,
  from_class varchar,
  to_id varchar,
  to_class varchar,
  is_bidirection boolean,
  status varchar DEFAULT 'ACTIVE',
  orientdb_id varchar,
  created_by bigint,
  created_name_by varchar,
  created_date timestamp,
  last_modified_by bigint,
  last_modified_name_by varchar,
  last_modified_date timestamp
);
alter table mes_entity_relation owner to postgres;
comment on table mes_entity_relation is '';
comment on column mes_entity_relation.id is 'id';
comment on column mes_entity_relation.from_id is '';
comment on column mes_entity_relation.from_class is '';
comment on column mes_entity_relation.to_id is '';
comment on column mes_entity_relation.to_class is '';
comment on column mes_entity_relation.is_bidirection is '';
comment on column mes_entity_relation.status is '状态';
comment on column mes_entity_relation.orientdb_id is 'orientDb中id';
comment on column mes_entity_relation.created_by is '创建人id';
comment on column mes_entity_relation.created_name_by is '创建人姓名';
comment on column mes_entity_relation.created_date is '创建时间';
comment on column mes_entity_relation.last_modified_by is '最后修改人id';
comment on column mes_entity_relation.last_modified_name_by is '最后修改人姓名';
comment on column mes_entity_relation.last_modified_date is '最后修改时间';


drop table if exists cf_action_common_data;
create table cf_action_common_data
(
  id bigserial primary key,
  action_id bigint,
  common_data_id bigint,
  status varchar DEFAULT 'ACTIVE',
  created_by bigint,
  created_name_by varchar,
  created_date timestamp,
  last_modified_by bigint,
  last_modified_name_by varchar,
  last_modified_date timestamp
);
alter table cf_action_common_data owner to postgres;
comment on table cf_action_common_data is '动作配置与公用数据中间表';
comment on column cf_action_common_data.id is 'id';
comment on column cf_action_common_data.action_id is '动作配置ID';
comment on column cf_action_common_data.common_data_id is '公用数据ID';
comment on column cf_action_common_data.status is '状态';
comment on column cf_action_common_data.created_by is '创建人id';
comment on column cf_action_common_data.created_name_by is '创建人姓名';
comment on column cf_action_common_data.created_date is '创建时间';
comment on column cf_action_common_data.last_modified_by is '最后修改人id';
comment on column cf_action_common_data.last_modified_name_by is '最后修改人姓名';
comment on column cf_action_common_data.last_modified_date is '最后修改时间';

CREATE  INDEX cf_action_common_data_action_id on cf_action_common_data (action_id);


drop table if exists common_data_http_headers;
create table common_data_http_headers
(
  id bigserial primary key,
  common_data_id bigint,
  code varchar,
  value varchar,
  status varchar DEFAULT 'ACTIVE',
  created_by bigint,
  created_name_by varchar,
  created_date timestamp,
  last_modified_by bigint,
  last_modified_name_by varchar,
  last_modified_date timestamp
);
alter table common_data_http_headers owner to postgres;
comment on table common_data_http_headers is '公用数据与Http请求外键表';
comment on column common_data_http_headers.id is 'id';
comment on column common_data_http_headers.common_data_id is '公用数据ID';
comment on column common_data_http_headers.code is 'Http请求头编码';
comment on column common_data_http_headers.value is 'Http请求头值';
comment on column common_data_http_headers.status is '状态';
comment on column common_data_http_headers.created_by is '创建人id';
comment on column common_data_http_headers.created_name_by is '创建人姓名';
comment on column common_data_http_headers.created_date is '创建时间';
comment on column common_data_http_headers.last_modified_by is '最后修改人id';
comment on column common_data_http_headers.last_modified_name_by is '最后修改人姓名';
comment on column common_data_http_headers.last_modified_date is '最后修改时间';

CREATE  INDEX common_data_http_headers_common_data_id on common_data_http_headers (common_data_id);


drop table if exists common_data_params;
create table common_data_params
(
  id bigserial primary key,
  common_data_id bigint,
  code varchar,
  value varchar,
  status varchar DEFAULT 'ACTIVE',
  created_by bigint,
  created_name_by varchar,
  created_date timestamp,
  last_modified_by bigint,
  last_modified_name_by varchar,
  last_modified_date timestamp
);
alter table common_data_params owner to postgres;
comment on table common_data_params is '公用数据与参数外键表';
comment on column common_data_params.id is 'id';
comment on column common_data_params.common_data_id is '公用数据ID';
comment on column common_data_params.code is '参数编码';
comment on column common_data_params.value is '参数值';
comment on column common_data_params.status is '状态';
comment on column common_data_params.created_by is '创建人id';
comment on column common_data_params.created_name_by is '创建人姓名';
comment on column common_data_params.created_date is '创建时间';
comment on column common_data_params.last_modified_by is '最后修改人id';
comment on column common_data_params.last_modified_name_by is '最后修改人姓名';
comment on column common_data_params.last_modified_date is '最后修改时间';

CREATE  INDEX common_data_params_common_data_id on common_data_params (common_data_id);


drop table if exists common_data_validation_http_headers;
create table common_data_validation_http_headers
(
  id bigserial primary key,
  common_data_validation_id bigint,
  code varchar,
  value varchar,
  status varchar DEFAULT 'ACTIVE',
  created_by bigint,
  created_name_by varchar,
  created_date timestamp,
  last_modified_by bigint,
  last_modified_name_by varchar,
  last_modified_date timestamp
);
alter table common_data_validation_http_headers owner to postgres;
comment on table common_data_validation_http_headers is '公用数据验证与Http请求外键表';
comment on column common_data_validation_http_headers.id is 'id';
comment on column common_data_validation_http_headers.common_data_validation_id is '公用数据验证ID';
comment on column common_data_validation_http_headers.code is 'Http请求头编码';
comment on column common_data_validation_http_headers.value is 'Http请求头值';
comment on column common_data_validation_http_headers.status is '状态';
comment on column common_data_validation_http_headers.created_by is '创建人id';
comment on column common_data_validation_http_headers.created_name_by is '创建人姓名';
comment on column common_data_validation_http_headers.created_date is '创建时间';
comment on column common_data_validation_http_headers.last_modified_by is '最后修改人id';
comment on column common_data_validation_http_headers.last_modified_name_by is '最后修改人姓名';
comment on column common_data_validation_http_headers.last_modified_date is '最后修改时间';

CREATE  INDEX common_data_validation_http_headers_common_data_validation_id on common_data_validation_http_headers (common_data_validation_id);


drop table if exists common_data_validation_param;
create table common_data_validation_param
(
  id bigserial primary key,
  common_data_id bigint,
  code varchar,
  value varchar,
  status varchar DEFAULT 'ACTIVE',
  created_by bigint,
  created_name_by varchar,
  created_date timestamp,
  last_modified_by bigint,
  last_modified_name_by varchar,
  last_modified_date timestamp
);
alter table common_data_validation_param owner to postgres;
comment on table common_data_validation_param is '公用数据验证与参数外键表';
comment on column common_data_validation_param.id is 'id';
comment on column common_data_validation_param.common_data_id is '公用数据验证ID';
comment on column common_data_validation_param.code is '参数编码';
comment on column common_data_validation_param.value is '参数值';
comment on column common_data_validation_param.status is '状态';
comment on column common_data_validation_param.created_by is '创建人id';
comment on column common_data_validation_param.created_name_by is '创建人姓名';
comment on column common_data_validation_param.created_date is '创建时间';
comment on column common_data_validation_param.last_modified_by is '最后修改人id';
comment on column common_data_validation_param.last_modified_name_by is '最后修改人姓名';
comment on column common_data_validation_param.last_modified_date is '最后修改时间';


drop table if exists mes_alert_subject_alert_message;
create table mes_alert_subject_alert_message
(
  id bigserial primary key,
  alert_subject_id bigint,
  alert_message_id bigint,
  status varchar DEFAULT 'ACTIVE',
  created_by bigint,
  created_name_by varchar,
  created_date timestamp,
  last_modified_by bigint,
  last_modified_name_by varchar,
  last_modified_date timestamp
);
alter table mes_alert_subject_alert_message owner to postgres;
comment on table mes_alert_subject_alert_message is '预警对象与提示消息中间表';
comment on column mes_alert_subject_alert_message.id is 'id';
comment on column mes_alert_subject_alert_message.alert_subject_id is '预警对象ID';
comment on column mes_alert_subject_alert_message.alert_message_id is '提示消息ID';
comment on column mes_alert_subject_alert_message.status is '状态';
comment on column mes_alert_subject_alert_message.created_by is '创建人id';
comment on column mes_alert_subject_alert_message.created_name_by is '创建人姓名';
comment on column mes_alert_subject_alert_message.created_date is '创建时间';
comment on column mes_alert_subject_alert_message.last_modified_by is '最后修改人id';
comment on column mes_alert_subject_alert_message.last_modified_name_by is '最后修改人姓名';
comment on column mes_alert_subject_alert_message.last_modified_date is '最后修改时间';

CREATE  INDEX mes_alert_subject_alert_message_alert_subject_id on mes_alert_subject_alert_message (alert_subject_id);

drop table if exists sys_info_log;
create table sys_info_log
(
  id bigserial primary key,
  cpu_name varchar,
  cpu_num int,
  cpu_used decimal,
  cpu_free decimal,

  mem_total decimal,
  mem_used DECIMAL,
  mem_free DECIMAL,
  mem_usage DECIMAL,

  sys_name varchar,
  sys_ip varchar,
  os_name VARCHAR,

  home_dir_total varchar,
  home_dir_free varchar,
  home_dir_used varchar,
  home_dir_usage DECIMAL,

  network_recv_speed bigint,
  network_send_speed bigint,

  disk_read_speed bigint,
  disk_write_speed bigint,

  status varchar DEFAULT 'ACTIVE',
  created_by bigint,
  created_name_by varchar,
  created_date timestamp,
  last_modified_by bigint,
  last_modified_name_by varchar,
  last_modified_date timestamp
);
alter table sys_info_log owner to postgres;
comment on table sys_info_log is '系统资源记录表';
comment on column sys_info_log.id is 'id';

comment on column sys_info_log.cpu_name is 'CPU名称';
comment on column sys_info_log.cpu_num is 'CPU数量';
comment on column sys_info_log.cpu_free is 'CPU空闲率';
comment on column sys_info_log.cpu_used is 'CPU使用率';

comment on column sys_info_log.mem_total is '内存容量';
comment on column sys_info_log.mem_used is '内存使用量';
comment on column sys_info_log.mem_free is '内存空闲量';
comment on column sys_info_log.mem_usage is '内存使用率';

comment on column sys_info_log.sys_name is '机器名';
comment on column sys_info_log.sys_ip is '机器IP';
comment on column sys_info_log.os_name is '操作系统';

comment on column sys_info_log.home_dir_total is '硬盘容量';
comment on column sys_info_log.home_dir_free is '硬盘空闲量';
comment on column sys_info_log.home_dir_used is '硬盘使用量';
comment on column sys_info_log.home_dir_usage is '硬盘使用率';

comment on column sys_info_log.network_recv_speed is '网卡接收速度';
comment on column sys_info_log.network_send_speed is '网卡发送速度';
comment on column sys_info_log.disk_read_speed is '硬盘读速度';
comment on column sys_info_log.disk_write_speed is '硬盘写速度';


comment on column sys_info_log.status is '状态';
comment on column sys_info_log.created_by is '创建人id';
comment on column sys_info_log.created_name_by is '创建人姓名';
comment on column sys_info_log.created_date is '创建时间';
comment on column sys_info_log.last_modified_by is '最后修改人id';
comment on column sys_info_log.last_modified_name_by is '最后修改人姓名';
comment on column sys_info_log.last_modified_date is '最后修改时间';

CREATE  INDEX sys_info_log_created_date on sys_info_log (created_date);


drop table if exists access_info_log;
create table access_info_log
(
  id bigserial primary key,
  module_name varchar,
  ip varchar,
  port int,
  access_count bigint,
  cost_time_millis_all bigint,
  avrg_time_millis bigint,
  access_speed decimal,

  status varchar DEFAULT 'ACTIVE',
  created_by bigint,
  created_name_by varchar,
  created_date timestamp,
  last_modified_by bigint,
  last_modified_name_by varchar,
  last_modified_date timestamp
);
alter table access_info_log owner to postgres;
comment on table access_info_log is '微服务访问统计表';
comment on column access_info_log.id is 'id';

comment on column access_info_log.module_name is '模块名称';
comment on column access_info_log.ip is '';
comment on column access_info_log.port is '端口';
comment on column access_info_log.access_count is '访问总数';
comment on column access_info_log.cost_time_millis_all is '总共用时';
comment on column access_info_log.avrg_time_millis is '平均用时';
comment on column access_info_log.access_speed is '速度,条/秒';

comment on column access_info_log.status is '状态';
comment on column access_info_log.created_by is '创建人id';
comment on column access_info_log.created_name_by is '创建人姓名';
comment on column access_info_log.created_date is '创建时间';
comment on column access_info_log.last_modified_by is '最后修改人id';
comment on column access_info_log.last_modified_name_by is '最后修改人姓名';
comment on column access_info_log.last_modified_date is '最后修改时间';

CREATE  INDEX access_info_log_created_date on access_info_log (created_date);



-- V5__message_step_batch.sql
-- 批次身份（#139 / ADR-0017）：消息步行上多出「批号 / 批内序号 / 父消息标识」三格。
--
-- 批号（batch_id）：**某一个动作上**、被同一次基数变化动作归到一起的那组消息的编号。
-- 每执行一次现生成（不按配置推导——否则改一次动作配置，历史里过去的分组会被重新解释），
-- 随消息在链上**继承到下一个基数变化动作为止**。读库那些行也带：每读一批一个新号，
-- 于是「哪几条是同一批」在库里答得出来（今天它只活在运行期的交换器属性里，一个字节都没落库）。
--
-- 批内序号（batch_seq）：**只在拆分与合并时写**（元素顺序 / 攒批顺序）。读取批里「第几条」
-- 没有额外语义，读库产生的那些行留空——空不是 0，别编一个 0 出来。
--
-- 父消息标识（parent_message_id）：管**谱系**（这条消息是从哪条消息拆出来的），与哪一次执行无关；
-- 批号管**分组**。一条父消息被拆两次时两次的子消息父消息标识完全相同，只有批号分得开
-- ——父消息标识不能反推分组，所以两格都不省（ADR-0017 决策 4）。
--
-- 三格都可空：老数据（这一列之前落库的消息步）没有它们，MQ 消费 / 任务输入那些链也永远没有批号。
--
-- 幂等：列与索引各自 information_schema 判断，已存在就 DO 0，与 V4 同一个写法
-- （手工建过列的库上执行也是 no-op）。
SET @db := DATABASE();

SET @sql := IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'message_step' AND COLUMN_NAME = 'batch_id'),
    'DO 0',
    'ALTER TABLE message_step ADD COLUMN batch_id VARCHAR(64) NULL COMMENT ''批号（#139）：这一步所属的那一批，随消息继承到下一个基数变化动作'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'message_step' AND COLUMN_NAME = 'batch_seq'),
    'DO 0',
    'ALTER TABLE message_step ADD COLUMN batch_seq INT NULL COMMENT ''批内序号（#139）：只在拆分与合并时写，读取批留空'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'message_step' AND COLUMN_NAME = 'parent_message_id'),
    'DO 0',
    'ALTER TABLE message_step ADD COLUMN parent_message_id VARCHAR(64) NULL COMMENT ''父消息标识（#139）：从哪条消息拆出来的，与哪一次执行无关'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 批号参与分组查询（右详情按批分组、按批核对这一批发过什么），要配套索引。
-- 唯一约束不动：批号不是标识的一部分，「运行 + 消息 + 步骤」仍然唯一。
SET @sql := IF(
    EXISTS (SELECT 1 FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'message_step' AND INDEX_NAME = 'idx_msg_step_batch'),
    'DO 0',
    'CREATE INDEX idx_msg_step_batch ON message_step (batch_id)'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

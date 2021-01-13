package com.sailheader.middleware.model;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class DataSourceExample {
    protected String orderByClause;

    protected boolean distinct;

    protected List<DataSourceExample.Criteria> oredCriteria;

    private Integer limit;

    private Long offset;

    public DataSourceExample() {
        oredCriteria = new ArrayList<DataSourceExample.Criteria>();
    }

    public void setOrderByClause(String orderByClause) {
        this.orderByClause = orderByClause;
    }

    public String getOrderByClause() {
        return orderByClause;
    }

    public void setDistinct(boolean distinct) {
        this.distinct = distinct;
    }

    public boolean isDistinct() {
        return distinct;
    }

    public List<DataSourceExample.Criteria> getOredCriteria() {
        return oredCriteria;
    }

    public void or(DataSourceExample.Criteria criteria) {
        oredCriteria.add(criteria);
    }

    public DataSourceExample.Criteria or() {
        DataSourceExample.Criteria criteria = createCriteriaInternal();
        oredCriteria.add(criteria);
        return criteria;
    }

    public DataSourceExample.Criteria createCriteria() {
        DataSourceExample.Criteria criteria = createCriteriaInternal();
        if (oredCriteria.size() == 0) {
            oredCriteria.add(criteria);
        }
        return criteria;
    }

    protected DataSourceExample.Criteria createCriteriaInternal() {
        DataSourceExample.Criteria criteria = new DataSourceExample.Criteria();
        return criteria;
    }

    public void clear() {
        oredCriteria.clear();
        orderByClause = null;
        distinct = false;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setOffset(Long offset) {
        this.offset = offset;
    }

    public Long getOffset() {
        return offset;
    }

    protected abstract static class GeneratedCriteria {
        protected List<DataSourceExample.Criterion> criteria;

        protected GeneratedCriteria() {
            super();
            criteria = new ArrayList<DataSourceExample.Criterion>();
        }

        public boolean isValid() {
            return criteria.size() > 0;
        }

        public List<DataSourceExample.Criterion> getAllCriteria() {
            return criteria;
        }

        public List<DataSourceExample.Criterion> getCriteria() {
            return criteria;
        }

        protected void addCriterion(String condition) {
            if (condition == null) {
                throw new RuntimeException("Value for condition cannot be null");
            }
            criteria.add(new DataSourceExample.Criterion(condition));
        }

        protected void addCriterion(String condition, Object value, String property) {
            if (value == null) {
                throw new RuntimeException("Value for " + property + " cannot be null");
            }
            criteria.add(new DataSourceExample.Criterion(condition, value));
        }

        protected void addCriterion(String condition, Object value1, Object value2, String property) {
            if (value1 == null || value2 == null) {
                throw new RuntimeException("Between values for " + property + " cannot be null");
            }
            criteria.add(new DataSourceExample.Criterion(condition, value1, value2));
        }

        public DataSourceExample.Criteria andIdIsNull() {
            addCriterion("id is null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andIdIsNotNull() {
            addCriterion("id is not null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andIdEqualTo(Long value) {
            addCriterion("id =", value, "id");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andIdNotEqualTo(Long value) {
            addCriterion("id <>", value, "id");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andIdGreaterThan(Long value) {
            addCriterion("id >", value, "id");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andIdGreaterThanOrEqualTo(Long value) {
            addCriterion("id >=", value, "id");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andIdLessThan(Long value) {
            addCriterion("id <", value, "id");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andIdLessThanOrEqualTo(Long value) {
            addCriterion("id <=", value, "id");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andIdIn(List<Long> values) {
            addCriterion("id in", values, "id");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andIdNotIn(List<Long> values) {
            addCriterion("id not in", values, "id");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andIdBetween(Long value1, Long value2) {
            addCriterion("id between", value1, value2, "id");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andIdNotBetween(Long value1, Long value2) {
            addCriterion("id not between", value1, value2, "id");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andTypeIsNull() {
            addCriterion("\"type\" is null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andTypeIsNotNull() {
            addCriterion("\"type\" is not null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andTypeEqualTo(String value) {
            addCriterion("\"type\" =", value, "type");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andTypeNotEqualTo(String value) {
            addCriterion("\"type\" <>", value, "type");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andTypeGreaterThan(String value) {
            addCriterion("\"type\" >", value, "type");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andTypeGreaterThanOrEqualTo(String value) {
            addCriterion("\"type\" >=", value, "type");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andTypeLessThan(String value) {
            addCriterion("\"type\" <", value, "type");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andTypeLessThanOrEqualTo(String value) {
            addCriterion("\"type\" <=", value, "type");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andTypeLike(String value) {
            addCriterion("\"type\" like", value, "type");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andTypeNotLike(String value) {
            addCriterion("\"type\" not like", value, "type");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andTypeIn(List<String> values) {
            addCriterion("\"type\" in", values, "type");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andTypeNotIn(List<String> values) {
            addCriterion("\"type\" not in", values, "type");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andTypeBetween(String value1, String value2) {
            addCriterion("\"type\" between", value1, value2, "type");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andTypeNotBetween(String value1, String value2) {
            addCriterion("\"type\" not between", value1, value2, "type");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andSequenceIsNull() {
            addCriterion("\"sequence\" is null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andSequenceIsNotNull() {
            addCriterion("\"sequence\" is not null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andSequenceEqualTo(Long value) {
            addCriterion("\"sequence\" =", value, "sequence");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andSequenceNotEqualTo(Long value) {
            addCriterion("\"sequence\" <>", value, "sequence");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andSequenceGreaterThan(Long value) {
            addCriterion("\"sequence\" >", value, "sequence");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andSequenceGreaterThanOrEqualTo(Long value) {
            addCriterion("\"sequence\" >=", value, "sequence");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andSequenceLessThan(Long value) {
            addCriterion("\"sequence\" <", value, "sequence");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andSequenceLessThanOrEqualTo(Long value) {
            addCriterion("\"sequence\" <=", value, "sequence");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andSequenceIn(List<Long> values) {
            addCriterion("\"sequence\" in", values, "sequence");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andSequenceNotIn(List<Long> values) {
            addCriterion("\"sequence\" not in", values, "sequence");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andSequenceBetween(Long value1, Long value2) {
            addCriterion("\"sequence\" between", value1, value2, "sequence");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andSequenceNotBetween(Long value1, Long value2) {
            addCriterion("\"sequence\" not between", value1, value2, "sequence");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnabledIsNull() {
            addCriterion("enabled is null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnabledIsNotNull() {
            addCriterion("enabled is not null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnabledEqualTo(Boolean value) {
            addCriterion("enabled =", value, "enabled");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnabledNotEqualTo(Boolean value) {
            addCriterion("enabled <>", value, "enabled");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnabledGreaterThan(Boolean value) {
            addCriterion("enabled >", value, "enabled");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnabledGreaterThanOrEqualTo(Boolean value) {
            addCriterion("enabled >=", value, "enabled");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnabledLessThan(Boolean value) {
            addCriterion("enabled <", value, "enabled");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnabledLessThanOrEqualTo(Boolean value) {
            addCriterion("enabled <=", value, "enabled");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnabledIn(List<Boolean> values) {
            addCriterion("enabled in", values, "enabled");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnabledNotIn(List<Boolean> values) {
            addCriterion("enabled not in", values, "enabled");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnabledBetween(Boolean value1, Boolean value2) {
            addCriterion("enabled between", value1, value2, "enabled");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnabledNotBetween(Boolean value1, Boolean value2) {
            addCriterion("enabled not between", value1, value2, "enabled");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andPinyinIsNull() {
            addCriterion("pinyin is null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andPinyinIsNotNull() {
            addCriterion("pinyin is not null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andPinyinEqualTo(String value) {
            addCriterion("pinyin =", value, "pinyin");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andPinyinNotEqualTo(String value) {
            addCriterion("pinyin <>", value, "pinyin");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andPinyinGreaterThan(String value) {
            addCriterion("pinyin >", value, "pinyin");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andPinyinGreaterThanOrEqualTo(String value) {
            addCriterion("pinyin >=", value, "pinyin");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andPinyinLessThan(String value) {
            addCriterion("pinyin <", value, "pinyin");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andPinyinLessThanOrEqualTo(String value) {
            addCriterion("pinyin <=", value, "pinyin");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andPinyinLike(String value) {
            addCriterion("pinyin like", value, "pinyin");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andPinyinNotLike(String value) {
            addCriterion("pinyin not like", value, "pinyin");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andPinyinIn(List<String> values) {
            addCriterion("pinyin in", values, "pinyin");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andPinyinNotIn(List<String> values) {
            addCriterion("pinyin not in", values, "pinyin");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andPinyinBetween(String value1, String value2) {
            addCriterion("pinyin between", value1, value2, "pinyin");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andPinyinNotBetween(String value1, String value2) {
            addCriterion("pinyin not between", value1, value2, "pinyin");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnUsNameIsNull() {
            addCriterion("en_us_name is null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnUsNameIsNotNull() {
            addCriterion("en_us_name is not null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnUsNameEqualTo(String value) {
            addCriterion("en_us_name =", value, "enUsName");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnUsNameNotEqualTo(String value) {
            addCriterion("en_us_name <>", value, "enUsName");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnUsNameGreaterThan(String value) {
            addCriterion("en_us_name >", value, "enUsName");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnUsNameGreaterThanOrEqualTo(String value) {
            addCriterion("en_us_name >=", value, "enUsName");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnUsNameLessThan(String value) {
            addCriterion("en_us_name <", value, "enUsName");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnUsNameLessThanOrEqualTo(String value) {
            addCriterion("en_us_name <=", value, "enUsName");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnUsNameLike(String value) {
            addCriterion("en_us_name like", value, "enUsName");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnUsNameNotLike(String value) {
            addCriterion("en_us_name not like", value, "enUsName");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnUsNameIn(List<String> values) {
            addCriterion("en_us_name in", values, "enUsName");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnUsNameNotIn(List<String> values) {
            addCriterion("en_us_name not in", values, "enUsName");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnUsNameBetween(String value1, String value2) {
            addCriterion("en_us_name between", value1, value2, "enUsName");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnUsNameNotBetween(String value1, String value2) {
            addCriterion("en_us_name not between", value1, value2, "enUsName");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andZhCnNameIsNull() {
            addCriterion("zh_cn_name is null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andZhCnNameIsNotNull() {
            addCriterion("zh_cn_name is not null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andZhCnNameEqualTo(String value) {
            addCriterion("zh_cn_name =", value, "zhCnName");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andZhCnNameNotEqualTo(String value) {
            addCriterion("zh_cn_name <>", value, "zhCnName");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andZhCnNameGreaterThan(String value) {
            addCriterion("zh_cn_name >", value, "zhCnName");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andZhCnNameGreaterThanOrEqualTo(String value) {
            addCriterion("zh_cn_name >=", value, "zhCnName");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andZhCnNameLessThan(String value) {
            addCriterion("zh_cn_name <", value, "zhCnName");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andZhCnNameLessThanOrEqualTo(String value) {
            addCriterion("zh_cn_name <=", value, "zhCnName");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andZhCnNameLike(String value) {
            addCriterion("zh_cn_name like", value, "zhCnName");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andZhCnNameNotLike(String value) {
            addCriterion("zh_cn_name not like", value, "zhCnName");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andZhCnNameIn(List<String> values) {
            addCriterion("zh_cn_name in", values, "zhCnName");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andZhCnNameNotIn(List<String> values) {
            addCriterion("zh_cn_name not in", values, "zhCnName");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andZhCnNameBetween(String value1, String value2) {
            addCriterion("zh_cn_name between", value1, value2, "zhCnName");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andZhCnNameNotBetween(String value1, String value2) {
            addCriterion("zh_cn_name not between", value1, value2, "zhCnName");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnUsContentIsNull() {
            addCriterion("en_us_content is null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnUsContentIsNotNull() {
            addCriterion("en_us_content is not null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnUsContentEqualTo(String value) {
            addCriterion("en_us_content =", value, "enUsContent");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnUsContentNotEqualTo(String value) {
            addCriterion("en_us_content <>", value, "enUsContent");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnUsContentGreaterThan(String value) {
            addCriterion("en_us_content >", value, "enUsContent");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnUsContentGreaterThanOrEqualTo(String value) {
            addCriterion("en_us_content >=", value, "enUsContent");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnUsContentLessThan(String value) {
            addCriterion("en_us_content <", value, "enUsContent");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnUsContentLessThanOrEqualTo(String value) {
            addCriterion("en_us_content <=", value, "enUsContent");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnUsContentLike(String value) {
            addCriterion("en_us_content like", value, "enUsContent");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnUsContentNotLike(String value) {
            addCriterion("en_us_content not like", value, "enUsContent");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnUsContentIn(List<String> values) {
            addCriterion("en_us_content in", values, "enUsContent");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnUsContentNotIn(List<String> values) {
            addCriterion("en_us_content not in", values, "enUsContent");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnUsContentBetween(String value1, String value2) {
            addCriterion("en_us_content between", value1, value2, "enUsContent");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andEnUsContentNotBetween(String value1, String value2) {
            addCriterion("en_us_content not between", value1, value2, "enUsContent");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andZhCnContentIsNull() {
            addCriterion("zh_cn_content is null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andZhCnContentIsNotNull() {
            addCriterion("zh_cn_content is not null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andZhCnContentEqualTo(String value) {
            addCriterion("zh_cn_content =", value, "zhCnContent");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andZhCnContentNotEqualTo(String value) {
            addCriterion("zh_cn_content <>", value, "zhCnContent");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andZhCnContentGreaterThan(String value) {
            addCriterion("zh_cn_content >", value, "zhCnContent");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andZhCnContentGreaterThanOrEqualTo(String value) {
            addCriterion("zh_cn_content >=", value, "zhCnContent");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andZhCnContentLessThan(String value) {
            addCriterion("zh_cn_content <", value, "zhCnContent");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andZhCnContentLessThanOrEqualTo(String value) {
            addCriterion("zh_cn_content <=", value, "zhCnContent");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andZhCnContentLike(String value) {
            addCriterion("zh_cn_content like", value, "zhCnContent");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andZhCnContentNotLike(String value) {
            addCriterion("zh_cn_content not like", value, "zhCnContent");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andZhCnContentIn(List<String> values) {
            addCriterion("zh_cn_content in", values, "zhCnContent");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andZhCnContentNotIn(List<String> values) {
            addCriterion("zh_cn_content not in", values, "zhCnContent");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andZhCnContentBetween(String value1, String value2) {
            addCriterion("zh_cn_content between", value1, value2, "zhCnContent");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andZhCnContentNotBetween(String value1, String value2) {
            addCriterion("zh_cn_content not between", value1, value2, "zhCnContent");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andStatusIsNull() {
            addCriterion("\"status\" is null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andStatusIsNotNull() {
            addCriterion("\"status\" is not null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andStatusEqualTo(Short value) {
            addCriterion("\"status\" =", value, "status");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andStatusNotEqualTo(String value) {
            addCriterion("\"status\" <>", value, "status");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andStatusGreaterThan(String value) {
            addCriterion("\"status\" >", value, "status");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andStatusGreaterThanOrEqualTo(String value) {
            addCriterion("\"status\" >=", value, "status");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andStatusLessThan(String value) {
            addCriterion("\"status\" <", value, "status");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andStatusLessThanOrEqualTo(String value) {
            addCriterion("\"status\" <=", value, "status");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andStatusLike(String value) {
            addCriterion("\"status\" like", value, "status");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andStatusNotLike(String value) {
            addCriterion("\"status\" not like", value, "status");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andStatusIn(List<String> values) {
            addCriterion("\"status\" in", values, "status");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andStatusNotIn(List<String> values) {
            addCriterion("\"status\" not in", values, "status");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andStatusBetween(String value1, String value2) {
            addCriterion("\"status\" between", value1, value2, "status");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andStatusNotBetween(String value1, String value2) {
            addCriterion("\"status\" not between", value1, value2, "status");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andOrientdbIdIsNull() {
            addCriterion("orientdb_id is null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andOrientdbIdIsNotNull() {
            addCriterion("orientdb_id is not null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andOrientdbIdEqualTo(String value) {
            addCriterion("orientdb_id =", value, "orientdbId");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andOrientdbIdNotEqualTo(String value) {
            addCriterion("orientdb_id <>", value, "orientdbId");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andOrientdbIdGreaterThan(String value) {
            addCriterion("orientdb_id >", value, "orientdbId");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andOrientdbIdGreaterThanOrEqualTo(String value) {
            addCriterion("orientdb_id >=", value, "orientdbId");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andOrientdbIdLessThan(String value) {
            addCriterion("orientdb_id <", value, "orientdbId");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andOrientdbIdLessThanOrEqualTo(String value) {
            addCriterion("orientdb_id <=", value, "orientdbId");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andOrientdbIdLike(String value) {
            addCriterion("orientdb_id like", value, "orientdbId");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andOrientdbIdNotLike(String value) {
            addCriterion("orientdb_id not like", value, "orientdbId");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andOrientdbIdIn(List<String> values) {
            addCriterion("orientdb_id in", values, "orientdbId");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andOrientdbIdNotIn(List<String> values) {
            addCriterion("orientdb_id not in", values, "orientdbId");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andOrientdbIdBetween(String value1, String value2) {
            addCriterion("orientdb_id between", value1, value2, "orientdbId");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andOrientdbIdNotBetween(String value1, String value2) {
            addCriterion("orientdb_id not between", value1, value2, "orientdbId");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedByIsNull() {
            addCriterion("created_by is null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedByIsNotNull() {
            addCriterion("created_by is not null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedByEqualTo(Long value) {
            addCriterion("created_by =", value, "createdBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedByNotEqualTo(Long value) {
            addCriterion("created_by <>", value, "createdBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedByGreaterThan(Long value) {
            addCriterion("created_by >", value, "createdBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedByGreaterThanOrEqualTo(Long value) {
            addCriterion("created_by >=", value, "createdBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedByLessThan(Long value) {
            addCriterion("created_by <", value, "createdBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedByLessThanOrEqualTo(Long value) {
            addCriterion("created_by <=", value, "createdBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedByIn(List<Long> values) {
            addCriterion("created_by in", values, "createdBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedByNotIn(List<Long> values) {
            addCriterion("created_by not in", values, "createdBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedByBetween(Long value1, Long value2) {
            addCriterion("created_by between", value1, value2, "createdBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedByNotBetween(Long value1, Long value2) {
            addCriterion("created_by not between", value1, value2, "createdBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedNameByIsNull() {
            addCriterion("created_name_by is null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedNameByIsNotNull() {
            addCriterion("created_name_by is not null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedNameByEqualTo(String value) {
            addCriterion("created_name_by =", value, "createdNameBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedNameByNotEqualTo(String value) {
            addCriterion("created_name_by <>", value, "createdNameBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedNameByGreaterThan(String value) {
            addCriterion("created_name_by >", value, "createdNameBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedNameByGreaterThanOrEqualTo(String value) {
            addCriterion("created_name_by >=", value, "createdNameBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedNameByLessThan(String value) {
            addCriterion("created_name_by <", value, "createdNameBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedNameByLessThanOrEqualTo(String value) {
            addCriterion("created_name_by <=", value, "createdNameBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedNameByLike(String value) {
            addCriterion("created_name_by like", value, "createdNameBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedNameByNotLike(String value) {
            addCriterion("created_name_by not like", value, "createdNameBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedNameByIn(List<String> values) {
            addCriterion("created_name_by in", values, "createdNameBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedNameByNotIn(List<String> values) {
            addCriterion("created_name_by not in", values, "createdNameBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedNameByBetween(String value1, String value2) {
            addCriterion("created_name_by between", value1, value2, "createdNameBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedNameByNotBetween(String value1, String value2) {
            addCriterion("created_name_by not between", value1, value2, "createdNameBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedDateIsNull() {
            addCriterion("created_date is null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedDateIsNotNull() {
            addCriterion("created_date is not null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedDateEqualTo(Date value) {
            addCriterion("created_date =", value, "createdDate");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedDateNotEqualTo(Date value) {
            addCriterion("created_date <>", value, "createdDate");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedDateGreaterThan(Date value) {
            addCriterion("created_date >", value, "createdDate");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedDateGreaterThanOrEqualTo(Date value) {
            addCriterion("created_date >=", value, "createdDate");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedDateLessThan(Date value) {
            addCriterion("created_date <", value, "createdDate");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedDateLessThanOrEqualTo(Date value) {
            addCriterion("created_date <=", value, "createdDate");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedDateIn(List<Date> values) {
            addCriterion("created_date in", values, "createdDate");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedDateNotIn(List<Date> values) {
            addCriterion("created_date not in", values, "createdDate");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedDateBetween(Date value1, Date value2) {
            addCriterion("created_date between", value1, value2, "createdDate");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andCreatedDateNotBetween(Date value1, Date value2) {
            addCriterion("created_date not between", value1, value2, "createdDate");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedByIsNull() {
            addCriterion("last_modified_by is null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedByIsNotNull() {
            addCriterion("last_modified_by is not null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedByEqualTo(Long value) {
            addCriterion("last_modified_by =", value, "lastModifiedBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedByNotEqualTo(Long value) {
            addCriterion("last_modified_by <>", value, "lastModifiedBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedByGreaterThan(Long value) {
            addCriterion("last_modified_by >", value, "lastModifiedBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedByGreaterThanOrEqualTo(Long value) {
            addCriterion("last_modified_by >=", value, "lastModifiedBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedByLessThan(Long value) {
            addCriterion("last_modified_by <", value, "lastModifiedBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedByLessThanOrEqualTo(Long value) {
            addCriterion("last_modified_by <=", value, "lastModifiedBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedByIn(List<Long> values) {
            addCriterion("last_modified_by in", values, "lastModifiedBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedByNotIn(List<Long> values) {
            addCriterion("last_modified_by not in", values, "lastModifiedBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedByBetween(Long value1, Long value2) {
            addCriterion("last_modified_by between", value1, value2, "lastModifiedBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedByNotBetween(Long value1, Long value2) {
            addCriterion("last_modified_by not between", value1, value2, "lastModifiedBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedNameByIsNull() {
            addCriterion("last_modified_name_by is null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedNameByIsNotNull() {
            addCriterion("last_modified_name_by is not null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedNameByEqualTo(String value) {
            addCriterion("last_modified_name_by =", value, "lastModifiedNameBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedNameByNotEqualTo(String value) {
            addCriterion("last_modified_name_by <>", value, "lastModifiedNameBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedNameByGreaterThan(String value) {
            addCriterion("last_modified_name_by >", value, "lastModifiedNameBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedNameByGreaterThanOrEqualTo(String value) {
            addCriterion("last_modified_name_by >=", value, "lastModifiedNameBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedNameByLessThan(String value) {
            addCriterion("last_modified_name_by <", value, "lastModifiedNameBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedNameByLessThanOrEqualTo(String value) {
            addCriterion("last_modified_name_by <=", value, "lastModifiedNameBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedNameByLike(String value) {
            addCriterion("last_modified_name_by like", value, "lastModifiedNameBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedNameByNotLike(String value) {
            addCriterion("last_modified_name_by not like", value, "lastModifiedNameBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedNameByIn(List<String> values) {
            addCriterion("last_modified_name_by in", values, "lastModifiedNameBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedNameByNotIn(List<String> values) {
            addCriterion("last_modified_name_by not in", values, "lastModifiedNameBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedNameByBetween(String value1, String value2) {
            addCriterion("last_modified_name_by between", value1, value2, "lastModifiedNameBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedNameByNotBetween(String value1, String value2) {
            addCriterion("last_modified_name_by not between", value1, value2, "lastModifiedNameBy");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedDateIsNull() {
            addCriterion("last_modified_date is null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedDateIsNotNull() {
            addCriterion("last_modified_date is not null");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedDateEqualTo(Date value) {
            addCriterion("last_modified_date =", value, "lastModifiedDate");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedDateNotEqualTo(Date value) {
            addCriterion("last_modified_date <>", value, "lastModifiedDate");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedDateGreaterThan(Date value) {
            addCriterion("last_modified_date >", value, "lastModifiedDate");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedDateGreaterThanOrEqualTo(Date value) {
            addCriterion("last_modified_date >=", value, "lastModifiedDate");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedDateLessThan(Date value) {
            addCriterion("last_modified_date <", value, "lastModifiedDate");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedDateLessThanOrEqualTo(Date value) {
            addCriterion("last_modified_date <=", value, "lastModifiedDate");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedDateIn(List<Date> values) {
            addCriterion("last_modified_date in", values, "lastModifiedDate");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedDateNotIn(List<Date> values) {
            addCriterion("last_modified_date not in", values, "lastModifiedDate");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedDateBetween(Date value1, Date value2) {
            addCriterion("last_modified_date between", value1, value2, "lastModifiedDate");
            return (DataSourceExample.Criteria) this;
        }

        public DataSourceExample.Criteria andLastModifiedDateNotBetween(Date value1, Date value2) {
            addCriterion("last_modified_date not between", value1, value2, "lastModifiedDate");
            return (DataSourceExample.Criteria) this;
        }
    }

    /**
     */
    public static class Criteria extends DataSourceExample.GeneratedCriteria {

        protected Criteria() {
            super();
        }
    }

    public static class Criterion {
        private String condition;

        private Object value;

        private Object secondValue;

        private boolean noValue;

        private boolean singleValue;

        private boolean betweenValue;

        private boolean listValue;

        private String typeHandler;

        public String getCondition() {
            return condition;
        }

        public Object getValue() {
            return value;
        }

        public Object getSecondValue() {
            return secondValue;
        }

        public boolean isNoValue() {
            return noValue;
        }

        public boolean isSingleValue() {
            return singleValue;
        }

        public boolean isBetweenValue() {
            return betweenValue;
        }

        public boolean isListValue() {
            return listValue;
        }

        public String getTypeHandler() {
            return typeHandler;
        }

        protected Criterion(String condition) {
            super();
            this.condition = condition;
            this.typeHandler = null;
            this.noValue = true;
        }

        protected Criterion(String condition, Object value, String typeHandler) {
            super();
            this.condition = condition;
            this.value = value;
            this.typeHandler = typeHandler;
            if (value instanceof List<?>) {
                this.listValue = true;
            } else {
                this.singleValue = true;
            }
        }

        protected Criterion(String condition, Object value) {
            this(condition, value, null);
        }

        protected Criterion(String condition, Object value, Object secondValue, String typeHandler) {
            super();
            this.condition = condition;
            this.value = value;
            this.secondValue = secondValue;
            this.typeHandler = typeHandler;
            this.betweenValue = true;
        }

        protected Criterion(String condition, Object value, Object secondValue) {
            this(condition, value, secondValue, null);
        }
    }
}

package com.sailheader.middleware.model;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class ImportJobExample {
    protected String orderByClause;

    protected boolean distinct;

    protected List<Criteria> oredCriteria;

    private Integer limit;

    private Long offset;

    public ImportJobExample() {
        oredCriteria = new ArrayList<Criteria>();
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

    public List<Criteria> getOredCriteria() {
        return oredCriteria;
    }

    public void or(Criteria criteria) {
        oredCriteria.add(criteria);
    }

    public Criteria or() {
        Criteria criteria = createCriteriaInternal();
        oredCriteria.add(criteria);
        return criteria;
    }

    public Criteria createCriteria() {
        Criteria criteria = createCriteriaInternal();
        if (oredCriteria.size() == 0) {
            oredCriteria.add(criteria);
        }
        return criteria;
    }

    protected Criteria createCriteriaInternal() {
        Criteria criteria = new Criteria();
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
        protected List<Criterion> criteria;

        protected GeneratedCriteria() {
            super();
            criteria = new ArrayList<Criterion>();
        }

        public boolean isValid() {
            return criteria.size() > 0;
        }

        public List<Criterion> getAllCriteria() {
            return criteria;
        }

        public List<Criterion> getCriteria() {
            return criteria;
        }

        protected void addCriterion(String condition) {
            if (condition == null) {
                throw new RuntimeException("Value for condition cannot be null");
            }
            criteria.add(new Criterion(condition));
        }

        protected void addCriterion(String condition, Object value, String property) {
            if (value == null) {
                throw new RuntimeException("Value for " + property + " cannot be null");
            }
            criteria.add(new Criterion(condition, value));
        }

        protected void addCriterion(String condition, Object value1, Object value2, String property) {
            if (value1 == null || value2 == null) {
                throw new RuntimeException("Between values for " + property + " cannot be null");
            }
            criteria.add(new Criterion(condition, value1, value2));
        }

        public Criteria andIdIsNull() {
            addCriterion("id is null");
            return (Criteria) this;
        }

        public Criteria andIdIsNotNull() {
            addCriterion("id is not null");
            return (Criteria) this;
        }

        public Criteria andIdEqualTo(Long value) {
            addCriterion("id =", value, "id");
            return (Criteria) this;
        }

        public Criteria andIdNotEqualTo(Long value) {
            addCriterion("id <>", value, "id");
            return (Criteria) this;
        }

        public Criteria andIdGreaterThan(Long value) {
            addCriterion("id >", value, "id");
            return (Criteria) this;
        }

        public Criteria andIdGreaterThanOrEqualTo(Long value) {
            addCriterion("id >=", value, "id");
            return (Criteria) this;
        }

        public Criteria andIdLessThan(Long value) {
            addCriterion("id <", value, "id");
            return (Criteria) this;
        }

        public Criteria andIdLessThanOrEqualTo(Long value) {
            addCriterion("id <=", value, "id");
            return (Criteria) this;
        }

        public Criteria andIdIn(List<Long> values) {
            addCriterion("id in", values, "id");
            return (Criteria) this;
        }

        public Criteria andIdNotIn(List<Long> values) {
            addCriterion("id not in", values, "id");
            return (Criteria) this;
        }

        public Criteria andIdBetween(Long value1, Long value2) {
            addCriterion("id between", value1, value2, "id");
            return (Criteria) this;
        }

        public Criteria andIdNotBetween(Long value1, Long value2) {
            addCriterion("id not between", value1, value2, "id");
            return (Criteria) this;
        }

        public Criteria andTypeIsNull() {
            addCriterion("\"type\" is null");
            return (Criteria) this;
        }

        public Criteria andTypeIsNotNull() {
            addCriterion("\"type\" is not null");
            return (Criteria) this;
        }

        public Criteria andTypeEqualTo(String value) {
            addCriterion("\"type\" =", value, "type");
            return (Criteria) this;
        }

        public Criteria andTypeNotEqualTo(String value) {
            addCriterion("\"type\" <>", value, "type");
            return (Criteria) this;
        }

        public Criteria andTypeGreaterThan(String value) {
            addCriterion("\"type\" >", value, "type");
            return (Criteria) this;
        }

        public Criteria andTypeGreaterThanOrEqualTo(String value) {
            addCriterion("\"type\" >=", value, "type");
            return (Criteria) this;
        }

        public Criteria andTypeLessThan(String value) {
            addCriterion("\"type\" <", value, "type");
            return (Criteria) this;
        }

        public Criteria andTypeLessThanOrEqualTo(String value) {
            addCriterion("\"type\" <=", value, "type");
            return (Criteria) this;
        }

        public Criteria andTypeLike(String value) {
            addCriterion("\"type\" like", value, "type");
            return (Criteria) this;
        }

        public Criteria andTypeNotLike(String value) {
            addCriterion("\"type\" not like", value, "type");
            return (Criteria) this;
        }

        public Criteria andTypeIn(List<String> values) {
            addCriterion("\"type\" in", values, "type");
            return (Criteria) this;
        }

        public Criteria andTypeNotIn(List<String> values) {
            addCriterion("\"type\" not in", values, "type");
            return (Criteria) this;
        }

        public Criteria andTypeBetween(String value1, String value2) {
            addCriterion("\"type\" between", value1, value2, "type");
            return (Criteria) this;
        }

        public Criteria andTypeNotBetween(String value1, String value2) {
            addCriterion("\"type\" not between", value1, value2, "type");
            return (Criteria) this;
        }

        public Criteria andSequenceIsNull() {
            addCriterion("\"sequence\" is null");
            return (Criteria) this;
        }

        public Criteria andSequenceIsNotNull() {
            addCriterion("\"sequence\" is not null");
            return (Criteria) this;
        }

        public Criteria andSequenceEqualTo(Long value) {
            addCriterion("\"sequence\" =", value, "sequence");
            return (Criteria) this;
        }

        public Criteria andSequenceNotEqualTo(Long value) {
            addCriterion("\"sequence\" <>", value, "sequence");
            return (Criteria) this;
        }

        public Criteria andSequenceGreaterThan(Long value) {
            addCriterion("\"sequence\" >", value, "sequence");
            return (Criteria) this;
        }

        public Criteria andSequenceGreaterThanOrEqualTo(Long value) {
            addCriterion("\"sequence\" >=", value, "sequence");
            return (Criteria) this;
        }

        public Criteria andSequenceLessThan(Long value) {
            addCriterion("\"sequence\" <", value, "sequence");
            return (Criteria) this;
        }

        public Criteria andSequenceLessThanOrEqualTo(Long value) {
            addCriterion("\"sequence\" <=", value, "sequence");
            return (Criteria) this;
        }

        public Criteria andSequenceIn(List<Long> values) {
            addCriterion("\"sequence\" in", values, "sequence");
            return (Criteria) this;
        }

        public Criteria andSequenceNotIn(List<Long> values) {
            addCriterion("\"sequence\" not in", values, "sequence");
            return (Criteria) this;
        }

        public Criteria andSequenceBetween(Long value1, Long value2) {
            addCriterion("\"sequence\" between", value1, value2, "sequence");
            return (Criteria) this;
        }

        public Criteria andSequenceNotBetween(Long value1, Long value2) {
            addCriterion("\"sequence\" not between", value1, value2, "sequence");
            return (Criteria) this;
        }

        public Criteria andEnabledIsNull() {
            addCriterion("enabled is null");
            return (Criteria) this;
        }

        public Criteria andEnabledIsNotNull() {
            addCriterion("enabled is not null");
            return (Criteria) this;
        }

        public Criteria andEnabledEqualTo(Boolean value) {
            addCriterion("enabled =", value, "enabled");
            return (Criteria) this;
        }

        public Criteria andEnabledNotEqualTo(Boolean value) {
            addCriterion("enabled <>", value, "enabled");
            return (Criteria) this;
        }

        public Criteria andEnabledGreaterThan(Boolean value) {
            addCriterion("enabled >", value, "enabled");
            return (Criteria) this;
        }

        public Criteria andEnabledGreaterThanOrEqualTo(Boolean value) {
            addCriterion("enabled >=", value, "enabled");
            return (Criteria) this;
        }

        public Criteria andEnabledLessThan(Boolean value) {
            addCriterion("enabled <", value, "enabled");
            return (Criteria) this;
        }

        public Criteria andEnabledLessThanOrEqualTo(Boolean value) {
            addCriterion("enabled <=", value, "enabled");
            return (Criteria) this;
        }

        public Criteria andEnabledIn(List<Boolean> values) {
            addCriterion("enabled in", values, "enabled");
            return (Criteria) this;
        }

        public Criteria andEnabledNotIn(List<Boolean> values) {
            addCriterion("enabled not in", values, "enabled");
            return (Criteria) this;
        }

        public Criteria andEnabledBetween(Boolean value1, Boolean value2) {
            addCriterion("enabled between", value1, value2, "enabled");
            return (Criteria) this;
        }

        public Criteria andEnabledNotBetween(Boolean value1, Boolean value2) {
            addCriterion("enabled not between", value1, value2, "enabled");
            return (Criteria) this;
        }

        public Criteria andPinyinIsNull() {
            addCriterion("pinyin is null");
            return (Criteria) this;
        }

        public Criteria andPinyinIsNotNull() {
            addCriterion("pinyin is not null");
            return (Criteria) this;
        }

        public Criteria andPinyinEqualTo(String value) {
            addCriterion("pinyin =", value, "pinyin");
            return (Criteria) this;
        }

        public Criteria andPinyinNotEqualTo(String value) {
            addCriterion("pinyin <>", value, "pinyin");
            return (Criteria) this;
        }

        public Criteria andPinyinGreaterThan(String value) {
            addCriterion("pinyin >", value, "pinyin");
            return (Criteria) this;
        }

        public Criteria andPinyinGreaterThanOrEqualTo(String value) {
            addCriterion("pinyin >=", value, "pinyin");
            return (Criteria) this;
        }

        public Criteria andPinyinLessThan(String value) {
            addCriterion("pinyin <", value, "pinyin");
            return (Criteria) this;
        }

        public Criteria andPinyinLessThanOrEqualTo(String value) {
            addCriterion("pinyin <=", value, "pinyin");
            return (Criteria) this;
        }

        public Criteria andPinyinLike(String value) {
            addCriterion("pinyin like", value, "pinyin");
            return (Criteria) this;
        }

        public Criteria andPinyinNotLike(String value) {
            addCriterion("pinyin not like", value, "pinyin");
            return (Criteria) this;
        }

        public Criteria andPinyinIn(List<String> values) {
            addCriterion("pinyin in", values, "pinyin");
            return (Criteria) this;
        }

        public Criteria andPinyinNotIn(List<String> values) {
            addCriterion("pinyin not in", values, "pinyin");
            return (Criteria) this;
        }

        public Criteria andPinyinBetween(String value1, String value2) {
            addCriterion("pinyin between", value1, value2, "pinyin");
            return (Criteria) this;
        }

        public Criteria andPinyinNotBetween(String value1, String value2) {
            addCriterion("pinyin not between", value1, value2, "pinyin");
            return (Criteria) this;
        }

        public Criteria andEnUsNameIsNull() {
            addCriterion("en_us_name is null");
            return (Criteria) this;
        }

        public Criteria andEnUsNameIsNotNull() {
            addCriterion("en_us_name is not null");
            return (Criteria) this;
        }

        public Criteria andEnUsNameEqualTo(String value) {
            addCriterion("en_us_name =", value, "enUsName");
            return (Criteria) this;
        }

        public Criteria andEnUsNameNotEqualTo(String value) {
            addCriterion("en_us_name <>", value, "enUsName");
            return (Criteria) this;
        }

        public Criteria andEnUsNameGreaterThan(String value) {
            addCriterion("en_us_name >", value, "enUsName");
            return (Criteria) this;
        }

        public Criteria andEnUsNameGreaterThanOrEqualTo(String value) {
            addCriterion("en_us_name >=", value, "enUsName");
            return (Criteria) this;
        }

        public Criteria andEnUsNameLessThan(String value) {
            addCriterion("en_us_name <", value, "enUsName");
            return (Criteria) this;
        }

        public Criteria andEnUsNameLessThanOrEqualTo(String value) {
            addCriterion("en_us_name <=", value, "enUsName");
            return (Criteria) this;
        }

        public Criteria andEnUsNameLike(String value) {
            addCriterion("en_us_name like", value, "enUsName");
            return (Criteria) this;
        }

        public Criteria andEnUsNameNotLike(String value) {
            addCriterion("en_us_name not like", value, "enUsName");
            return (Criteria) this;
        }

        public Criteria andEnUsNameIn(List<String> values) {
            addCriterion("en_us_name in", values, "enUsName");
            return (Criteria) this;
        }

        public Criteria andEnUsNameNotIn(List<String> values) {
            addCriterion("en_us_name not in", values, "enUsName");
            return (Criteria) this;
        }

        public Criteria andEnUsNameBetween(String value1, String value2) {
            addCriterion("en_us_name between", value1, value2, "enUsName");
            return (Criteria) this;
        }

        public Criteria andEnUsNameNotBetween(String value1, String value2) {
            addCriterion("en_us_name not between", value1, value2, "enUsName");
            return (Criteria) this;
        }

        public Criteria andZhCnNameIsNull() {
            addCriterion("zh_cn_name is null");
            return (Criteria) this;
        }

        public Criteria andZhCnNameIsNotNull() {
            addCriterion("zh_cn_name is not null");
            return (Criteria) this;
        }

        public Criteria andZhCnNameEqualTo(String value) {
            addCriterion("zh_cn_name =", value, "zhCnName");
            return (Criteria) this;
        }

        public Criteria andZhCnNameNotEqualTo(String value) {
            addCriterion("zh_cn_name <>", value, "zhCnName");
            return (Criteria) this;
        }

        public Criteria andZhCnNameGreaterThan(String value) {
            addCriterion("zh_cn_name >", value, "zhCnName");
            return (Criteria) this;
        }

        public Criteria andZhCnNameGreaterThanOrEqualTo(String value) {
            addCriterion("zh_cn_name >=", value, "zhCnName");
            return (Criteria) this;
        }

        public Criteria andZhCnNameLessThan(String value) {
            addCriterion("zh_cn_name <", value, "zhCnName");
            return (Criteria) this;
        }

        public Criteria andZhCnNameLessThanOrEqualTo(String value) {
            addCriterion("zh_cn_name <=", value, "zhCnName");
            return (Criteria) this;
        }

        public Criteria andZhCnNameLike(String value) {
            addCriterion("zh_cn_name like", value, "zhCnName");
            return (Criteria) this;
        }

        public Criteria andZhCnNameNotLike(String value) {
            addCriterion("zh_cn_name not like", value, "zhCnName");
            return (Criteria) this;
        }

        public Criteria andZhCnNameIn(List<String> values) {
            addCriterion("zh_cn_name in", values, "zhCnName");
            return (Criteria) this;
        }

        public Criteria andZhCnNameNotIn(List<String> values) {
            addCriterion("zh_cn_name not in", values, "zhCnName");
            return (Criteria) this;
        }

        public Criteria andZhCnNameBetween(String value1, String value2) {
            addCriterion("zh_cn_name between", value1, value2, "zhCnName");
            return (Criteria) this;
        }

        public Criteria andZhCnNameNotBetween(String value1, String value2) {
            addCriterion("zh_cn_name not between", value1, value2, "zhCnName");
            return (Criteria) this;
        }

        public Criteria andEnUsContentIsNull() {
            addCriterion("en_us_content is null");
            return (Criteria) this;
        }

        public Criteria andEnUsContentIsNotNull() {
            addCriterion("en_us_content is not null");
            return (Criteria) this;
        }

        public Criteria andEnUsContentEqualTo(String value) {
            addCriterion("en_us_content =", value, "enUsContent");
            return (Criteria) this;
        }

        public Criteria andEnUsContentNotEqualTo(String value) {
            addCriterion("en_us_content <>", value, "enUsContent");
            return (Criteria) this;
        }

        public Criteria andEnUsContentGreaterThan(String value) {
            addCriterion("en_us_content >", value, "enUsContent");
            return (Criteria) this;
        }

        public Criteria andEnUsContentGreaterThanOrEqualTo(String value) {
            addCriterion("en_us_content >=", value, "enUsContent");
            return (Criteria) this;
        }

        public Criteria andEnUsContentLessThan(String value) {
            addCriterion("en_us_content <", value, "enUsContent");
            return (Criteria) this;
        }

        public Criteria andEnUsContentLessThanOrEqualTo(String value) {
            addCriterion("en_us_content <=", value, "enUsContent");
            return (Criteria) this;
        }

        public Criteria andEnUsContentLike(String value) {
            addCriterion("en_us_content like", value, "enUsContent");
            return (Criteria) this;
        }

        public Criteria andEnUsContentNotLike(String value) {
            addCriterion("en_us_content not like", value, "enUsContent");
            return (Criteria) this;
        }

        public Criteria andEnUsContentIn(List<String> values) {
            addCriterion("en_us_content in", values, "enUsContent");
            return (Criteria) this;
        }

        public Criteria andEnUsContentNotIn(List<String> values) {
            addCriterion("en_us_content not in", values, "enUsContent");
            return (Criteria) this;
        }

        public Criteria andEnUsContentBetween(String value1, String value2) {
            addCriterion("en_us_content between", value1, value2, "enUsContent");
            return (Criteria) this;
        }

        public Criteria andEnUsContentNotBetween(String value1, String value2) {
            addCriterion("en_us_content not between", value1, value2, "enUsContent");
            return (Criteria) this;
        }

        public Criteria andZhCnContentIsNull() {
            addCriterion("zh_cn_content is null");
            return (Criteria) this;
        }

        public Criteria andZhCnContentIsNotNull() {
            addCriterion("zh_cn_content is not null");
            return (Criteria) this;
        }

        public Criteria andZhCnContentEqualTo(String value) {
            addCriterion("zh_cn_content =", value, "zhCnContent");
            return (Criteria) this;
        }

        public Criteria andZhCnContentNotEqualTo(String value) {
            addCriterion("zh_cn_content <>", value, "zhCnContent");
            return (Criteria) this;
        }

        public Criteria andZhCnContentGreaterThan(String value) {
            addCriterion("zh_cn_content >", value, "zhCnContent");
            return (Criteria) this;
        }

        public Criteria andZhCnContentGreaterThanOrEqualTo(String value) {
            addCriterion("zh_cn_content >=", value, "zhCnContent");
            return (Criteria) this;
        }

        public Criteria andZhCnContentLessThan(String value) {
            addCriterion("zh_cn_content <", value, "zhCnContent");
            return (Criteria) this;
        }

        public Criteria andZhCnContentLessThanOrEqualTo(String value) {
            addCriterion("zh_cn_content <=", value, "zhCnContent");
            return (Criteria) this;
        }

        public Criteria andZhCnContentLike(String value) {
            addCriterion("zh_cn_content like", value, "zhCnContent");
            return (Criteria) this;
        }

        public Criteria andZhCnContentNotLike(String value) {
            addCriterion("zh_cn_content not like", value, "zhCnContent");
            return (Criteria) this;
        }

        public Criteria andZhCnContentIn(List<String> values) {
            addCriterion("zh_cn_content in", values, "zhCnContent");
            return (Criteria) this;
        }

        public Criteria andZhCnContentNotIn(List<String> values) {
            addCriterion("zh_cn_content not in", values, "zhCnContent");
            return (Criteria) this;
        }

        public Criteria andZhCnContentBetween(String value1, String value2) {
            addCriterion("zh_cn_content between", value1, value2, "zhCnContent");
            return (Criteria) this;
        }

        public Criteria andZhCnContentNotBetween(String value1, String value2) {
            addCriterion("zh_cn_content not between", value1, value2, "zhCnContent");
            return (Criteria) this;
        }

        public Criteria andStatusIsNull() {
            addCriterion("\"status\" is null");
            return (Criteria) this;
        }

        public Criteria andStatusIsNotNull() {
            addCriterion("\"status\" is not null");
            return (Criteria) this;
        }

        public Criteria andStatusEqualTo(String value) {
            addCriterion("\"status\" =", value, "status");
            return (Criteria) this;
        }

        public Criteria andStatusNotEqualTo(String value) {
            addCriterion("\"status\" <>", value, "status");
            return (Criteria) this;
        }

        public Criteria andStatusGreaterThan(String value) {
            addCriterion("\"status\" >", value, "status");
            return (Criteria) this;
        }

        public Criteria andStatusGreaterThanOrEqualTo(String value) {
            addCriterion("\"status\" >=", value, "status");
            return (Criteria) this;
        }

        public Criteria andStatusLessThan(String value) {
            addCriterion("\"status\" <", value, "status");
            return (Criteria) this;
        }

        public Criteria andStatusLessThanOrEqualTo(String value) {
            addCriterion("\"status\" <=", value, "status");
            return (Criteria) this;
        }

        public Criteria andStatusLike(String value) {
            addCriterion("\"status\" like", value, "status");
            return (Criteria) this;
        }

        public Criteria andStatusNotLike(String value) {
            addCriterion("\"status\" not like", value, "status");
            return (Criteria) this;
        }

        public Criteria andStatusIn(List<String> values) {
            addCriterion("\"status\" in", values, "status");
            return (Criteria) this;
        }

        public Criteria andStatusNotIn(List<String> values) {
            addCriterion("\"status\" not in", values, "status");
            return (Criteria) this;
        }

        public Criteria andStatusBetween(String value1, String value2) {
            addCriterion("\"status\" between", value1, value2, "status");
            return (Criteria) this;
        }

        public Criteria andStatusNotBetween(String value1, String value2) {
            addCriterion("\"status\" not between", value1, value2, "status");
            return (Criteria) this;
        }

        public Criteria andOrientdbIdIsNull() {
            addCriterion("orientdb_id is null");
            return (Criteria) this;
        }

        public Criteria andOrientdbIdIsNotNull() {
            addCriterion("orientdb_id is not null");
            return (Criteria) this;
        }

        public Criteria andOrientdbIdEqualTo(String value) {
            addCriterion("orientdb_id =", value, "orientdbId");
            return (Criteria) this;
        }

        public Criteria andOrientdbIdNotEqualTo(String value) {
            addCriterion("orientdb_id <>", value, "orientdbId");
            return (Criteria) this;
        }

        public Criteria andOrientdbIdGreaterThan(String value) {
            addCriterion("orientdb_id >", value, "orientdbId");
            return (Criteria) this;
        }

        public Criteria andOrientdbIdGreaterThanOrEqualTo(String value) {
            addCriterion("orientdb_id >=", value, "orientdbId");
            return (Criteria) this;
        }

        public Criteria andOrientdbIdLessThan(String value) {
            addCriterion("orientdb_id <", value, "orientdbId");
            return (Criteria) this;
        }

        public Criteria andOrientdbIdLessThanOrEqualTo(String value) {
            addCriterion("orientdb_id <=", value, "orientdbId");
            return (Criteria) this;
        }

        public Criteria andOrientdbIdLike(String value) {
            addCriterion("orientdb_id like", value, "orientdbId");
            return (Criteria) this;
        }

        public Criteria andOrientdbIdNotLike(String value) {
            addCriterion("orientdb_id not like", value, "orientdbId");
            return (Criteria) this;
        }

        public Criteria andOrientdbIdIn(List<String> values) {
            addCriterion("orientdb_id in", values, "orientdbId");
            return (Criteria) this;
        }

        public Criteria andOrientdbIdNotIn(List<String> values) {
            addCriterion("orientdb_id not in", values, "orientdbId");
            return (Criteria) this;
        }

        public Criteria andOrientdbIdBetween(String value1, String value2) {
            addCriterion("orientdb_id between", value1, value2, "orientdbId");
            return (Criteria) this;
        }

        public Criteria andOrientdbIdNotBetween(String value1, String value2) {
            addCriterion("orientdb_id not between", value1, value2, "orientdbId");
            return (Criteria) this;
        }

        public Criteria andCreatedByIsNull() {
            addCriterion("created_by is null");
            return (Criteria) this;
        }

        public Criteria andCreatedByIsNotNull() {
            addCriterion("created_by is not null");
            return (Criteria) this;
        }

        public Criteria andCreatedByEqualTo(Long value) {
            addCriterion("created_by =", value, "createdBy");
            return (Criteria) this;
        }

        public Criteria andCreatedByNotEqualTo(Long value) {
            addCriterion("created_by <>", value, "createdBy");
            return (Criteria) this;
        }

        public Criteria andCreatedByGreaterThan(Long value) {
            addCriterion("created_by >", value, "createdBy");
            return (Criteria) this;
        }

        public Criteria andCreatedByGreaterThanOrEqualTo(Long value) {
            addCriterion("created_by >=", value, "createdBy");
            return (Criteria) this;
        }

        public Criteria andCreatedByLessThan(Long value) {
            addCriterion("created_by <", value, "createdBy");
            return (Criteria) this;
        }

        public Criteria andCreatedByLessThanOrEqualTo(Long value) {
            addCriterion("created_by <=", value, "createdBy");
            return (Criteria) this;
        }

        public Criteria andCreatedByIn(List<Long> values) {
            addCriterion("created_by in", values, "createdBy");
            return (Criteria) this;
        }

        public Criteria andCreatedByNotIn(List<Long> values) {
            addCriterion("created_by not in", values, "createdBy");
            return (Criteria) this;
        }

        public Criteria andCreatedByBetween(Long value1, Long value2) {
            addCriterion("created_by between", value1, value2, "createdBy");
            return (Criteria) this;
        }

        public Criteria andCreatedByNotBetween(Long value1, Long value2) {
            addCriterion("created_by not between", value1, value2, "createdBy");
            return (Criteria) this;
        }

        public Criteria andCreatedNameByIsNull() {
            addCriterion("created_name_by is null");
            return (Criteria) this;
        }

        public Criteria andCreatedNameByIsNotNull() {
            addCriterion("created_name_by is not null");
            return (Criteria) this;
        }

        public Criteria andCreatedNameByEqualTo(String value) {
            addCriterion("created_name_by =", value, "createdNameBy");
            return (Criteria) this;
        }

        public Criteria andCreatedNameByNotEqualTo(String value) {
            addCriterion("created_name_by <>", value, "createdNameBy");
            return (Criteria) this;
        }

        public Criteria andCreatedNameByGreaterThan(String value) {
            addCriterion("created_name_by >", value, "createdNameBy");
            return (Criteria) this;
        }

        public Criteria andCreatedNameByGreaterThanOrEqualTo(String value) {
            addCriterion("created_name_by >=", value, "createdNameBy");
            return (Criteria) this;
        }

        public Criteria andCreatedNameByLessThan(String value) {
            addCriterion("created_name_by <", value, "createdNameBy");
            return (Criteria) this;
        }

        public Criteria andCreatedNameByLessThanOrEqualTo(String value) {
            addCriterion("created_name_by <=", value, "createdNameBy");
            return (Criteria) this;
        }

        public Criteria andCreatedNameByLike(String value) {
            addCriterion("created_name_by like", value, "createdNameBy");
            return (Criteria) this;
        }

        public Criteria andCreatedNameByNotLike(String value) {
            addCriterion("created_name_by not like", value, "createdNameBy");
            return (Criteria) this;
        }

        public Criteria andCreatedNameByIn(List<String> values) {
            addCriterion("created_name_by in", values, "createdNameBy");
            return (Criteria) this;
        }

        public Criteria andCreatedNameByNotIn(List<String> values) {
            addCriterion("created_name_by not in", values, "createdNameBy");
            return (Criteria) this;
        }

        public Criteria andCreatedNameByBetween(String value1, String value2) {
            addCriterion("created_name_by between", value1, value2, "createdNameBy");
            return (Criteria) this;
        }

        public Criteria andCreatedNameByNotBetween(String value1, String value2) {
            addCriterion("created_name_by not between", value1, value2, "createdNameBy");
            return (Criteria) this;
        }

        public Criteria andCreatedDateIsNull() {
            addCriterion("created_date is null");
            return (Criteria) this;
        }

        public Criteria andCreatedDateIsNotNull() {
            addCriterion("created_date is not null");
            return (Criteria) this;
        }

        public Criteria andCreatedDateEqualTo(Date value) {
            addCriterion("created_date =", value, "createdDate");
            return (Criteria) this;
        }

        public Criteria andCreatedDateNotEqualTo(Date value) {
            addCriterion("created_date <>", value, "createdDate");
            return (Criteria) this;
        }

        public Criteria andCreatedDateGreaterThan(Date value) {
            addCriterion("created_date >", value, "createdDate");
            return (Criteria) this;
        }

        public Criteria andCreatedDateGreaterThanOrEqualTo(Date value) {
            addCriterion("created_date >=", value, "createdDate");
            return (Criteria) this;
        }

        public Criteria andCreatedDateLessThan(Date value) {
            addCriterion("created_date <", value, "createdDate");
            return (Criteria) this;
        }

        public Criteria andCreatedDateLessThanOrEqualTo(Date value) {
            addCriterion("created_date <=", value, "createdDate");
            return (Criteria) this;
        }

        public Criteria andCreatedDateIn(List<Date> values) {
            addCriterion("created_date in", values, "createdDate");
            return (Criteria) this;
        }

        public Criteria andCreatedDateNotIn(List<Date> values) {
            addCriterion("created_date not in", values, "createdDate");
            return (Criteria) this;
        }

        public Criteria andCreatedDateBetween(Date value1, Date value2) {
            addCriterion("created_date between", value1, value2, "createdDate");
            return (Criteria) this;
        }

        public Criteria andCreatedDateNotBetween(Date value1, Date value2) {
            addCriterion("created_date not between", value1, value2, "createdDate");
            return (Criteria) this;
        }

        public Criteria andLastModifiedByIsNull() {
            addCriterion("last_modified_by is null");
            return (Criteria) this;
        }

        public Criteria andLastModifiedByIsNotNull() {
            addCriterion("last_modified_by is not null");
            return (Criteria) this;
        }

        public Criteria andLastModifiedByEqualTo(Long value) {
            addCriterion("last_modified_by =", value, "lastModifiedBy");
            return (Criteria) this;
        }

        public Criteria andLastModifiedByNotEqualTo(Long value) {
            addCriterion("last_modified_by <>", value, "lastModifiedBy");
            return (Criteria) this;
        }

        public Criteria andLastModifiedByGreaterThan(Long value) {
            addCriterion("last_modified_by >", value, "lastModifiedBy");
            return (Criteria) this;
        }

        public Criteria andLastModifiedByGreaterThanOrEqualTo(Long value) {
            addCriterion("last_modified_by >=", value, "lastModifiedBy");
            return (Criteria) this;
        }

        public Criteria andLastModifiedByLessThan(Long value) {
            addCriterion("last_modified_by <", value, "lastModifiedBy");
            return (Criteria) this;
        }

        public Criteria andLastModifiedByLessThanOrEqualTo(Long value) {
            addCriterion("last_modified_by <=", value, "lastModifiedBy");
            return (Criteria) this;
        }

        public Criteria andLastModifiedByIn(List<Long> values) {
            addCriterion("last_modified_by in", values, "lastModifiedBy");
            return (Criteria) this;
        }

        public Criteria andLastModifiedByNotIn(List<Long> values) {
            addCriterion("last_modified_by not in", values, "lastModifiedBy");
            return (Criteria) this;
        }

        public Criteria andLastModifiedByBetween(Long value1, Long value2) {
            addCriterion("last_modified_by between", value1, value2, "lastModifiedBy");
            return (Criteria) this;
        }

        public Criteria andLastModifiedByNotBetween(Long value1, Long value2) {
            addCriterion("last_modified_by not between", value1, value2, "lastModifiedBy");
            return (Criteria) this;
        }

        public Criteria andLastModifiedNameByIsNull() {
            addCriterion("last_modified_name_by is null");
            return (Criteria) this;
        }

        public Criteria andLastModifiedNameByIsNotNull() {
            addCriterion("last_modified_name_by is not null");
            return (Criteria) this;
        }

        public Criteria andLastModifiedNameByEqualTo(String value) {
            addCriterion("last_modified_name_by =", value, "lastModifiedNameBy");
            return (Criteria) this;
        }

        public Criteria andLastModifiedNameByNotEqualTo(String value) {
            addCriterion("last_modified_name_by <>", value, "lastModifiedNameBy");
            return (Criteria) this;
        }

        public Criteria andLastModifiedNameByGreaterThan(String value) {
            addCriterion("last_modified_name_by >", value, "lastModifiedNameBy");
            return (Criteria) this;
        }

        public Criteria andLastModifiedNameByGreaterThanOrEqualTo(String value) {
            addCriterion("last_modified_name_by >=", value, "lastModifiedNameBy");
            return (Criteria) this;
        }

        public Criteria andLastModifiedNameByLessThan(String value) {
            addCriterion("last_modified_name_by <", value, "lastModifiedNameBy");
            return (Criteria) this;
        }

        public Criteria andLastModifiedNameByLessThanOrEqualTo(String value) {
            addCriterion("last_modified_name_by <=", value, "lastModifiedNameBy");
            return (Criteria) this;
        }

        public Criteria andLastModifiedNameByLike(String value) {
            addCriterion("last_modified_name_by like", value, "lastModifiedNameBy");
            return (Criteria) this;
        }

        public Criteria andLastModifiedNameByNotLike(String value) {
            addCriterion("last_modified_name_by not like", value, "lastModifiedNameBy");
            return (Criteria) this;
        }

        public Criteria andLastModifiedNameByIn(List<String> values) {
            addCriterion("last_modified_name_by in", values, "lastModifiedNameBy");
            return (Criteria) this;
        }

        public Criteria andLastModifiedNameByNotIn(List<String> values) {
            addCriterion("last_modified_name_by not in", values, "lastModifiedNameBy");
            return (Criteria) this;
        }

        public Criteria andLastModifiedNameByBetween(String value1, String value2) {
            addCriterion("last_modified_name_by between", value1, value2, "lastModifiedNameBy");
            return (Criteria) this;
        }

        public Criteria andLastModifiedNameByNotBetween(String value1, String value2) {
            addCriterion("last_modified_name_by not between", value1, value2, "lastModifiedNameBy");
            return (Criteria) this;
        }

        public Criteria andLastModifiedDateIsNull() {
            addCriterion("last_modified_date is null");
            return (Criteria) this;
        }

        public Criteria andLastModifiedDateIsNotNull() {
            addCriterion("last_modified_date is not null");
            return (Criteria) this;
        }

        public Criteria andLastModifiedDateEqualTo(Date value) {
            addCriterion("last_modified_date =", value, "lastModifiedDate");
            return (Criteria) this;
        }

        public Criteria andLastModifiedDateNotEqualTo(Date value) {
            addCriterion("last_modified_date <>", value, "lastModifiedDate");
            return (Criteria) this;
        }

        public Criteria andLastModifiedDateGreaterThan(Date value) {
            addCriterion("last_modified_date >", value, "lastModifiedDate");
            return (Criteria) this;
        }

        public Criteria andLastModifiedDateGreaterThanOrEqualTo(Date value) {
            addCriterion("last_modified_date >=", value, "lastModifiedDate");
            return (Criteria) this;
        }

        public Criteria andLastModifiedDateLessThan(Date value) {
            addCriterion("last_modified_date <", value, "lastModifiedDate");
            return (Criteria) this;
        }

        public Criteria andLastModifiedDateLessThanOrEqualTo(Date value) {
            addCriterion("last_modified_date <=", value, "lastModifiedDate");
            return (Criteria) this;
        }

        public Criteria andLastModifiedDateIn(List<Date> values) {
            addCriterion("last_modified_date in", values, "lastModifiedDate");
            return (Criteria) this;
        }

        public Criteria andLastModifiedDateNotIn(List<Date> values) {
            addCriterion("last_modified_date not in", values, "lastModifiedDate");
            return (Criteria) this;
        }

        public Criteria andLastModifiedDateBetween(Date value1, Date value2) {
            addCriterion("last_modified_date between", value1, value2, "lastModifiedDate");
            return (Criteria) this;
        }

        public Criteria andLastModifiedDateNotBetween(Date value1, Date value2) {
            addCriterion("last_modified_date not between", value1, value2, "lastModifiedDate");
            return (Criteria) this;
        }
    }

    /**
     */
    public static class Criteria extends GeneratedCriteria {

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

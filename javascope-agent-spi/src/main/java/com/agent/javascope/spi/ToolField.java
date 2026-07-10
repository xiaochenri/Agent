package com.agent.javascope.spi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 用于从强类型入参对象或 record 推断工具 input_schema 的字段级描述。 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolField {

    /** 字段用途说明，会进入 JSON Schema description。 */
    String description() default "";

    /** 是否为必填字段，会进入 JSON Schema required。 */
    boolean required() default false;

    /** 允许值列表，会进入 JSON Schema enum。 */
    String[] enums() default {};

    /** 默认值说明，会进入 JSON Schema default。 */
    String defaultValue() default "";
}

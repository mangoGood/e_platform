package com.ecommerce.common.config;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 审计字段（{@code createTime} / {@code updateTime}）自动填充处理器。
 *
 * <h3>为什么需要它（BUG-1 根因）</h3>
 * 全仓共有 <b>9 个实体</b>（Order / OrderItem / Address / Cart / Product / Category /
 * Comment / Review / User）在审计字段上声明了
 * {@code @TableField(fill = FieldFill.INSERT[_UPDATE])}，
 * 但此前<b>没有任何 {@link MetaObjectHandler} 实现</b>。
 *
 * <p>该注解的语义是「这一列交给填充器负责」，MyBatis-Plus 因此把它
 * <b>强制加入 INSERT 的列清单</b>（绕过默认的 {@code NOT_NULL} 字段策略）。
 * 没有填充器兜底时，写进去的就是一个显式的 {@code NULL}，
 * 反而<b>把建表语句里的 {@code DEFAULT CURRENT_TIMESTAMP} 覆盖掉了</b>。
 *
 * <p>后果是 {@code orders} 表 280/280 行 {@code create_time IS NULL}，
 * 而依赖 {@code ORDER BY create_time DESC} 的分页查询因为排序键<b>全部并列</b>而失效：
 * MySQL 在没有确定序时按执行计划返回行，{@code LIMIT/OFFSET} 跨页时
 * 同一行可能重复出现、另一行则永远被跳过 —— 表现为「翻到底也有 20% 的订单看不见」。
 *
 * <h3>为什么选「补填充器」而不是「删注解靠 DB 默认值」</h3>
 * <ol>
 *   <li><b>改动面更小、更忠于原意</b>：9 个实体共 15 处注解表达的就是
 *       「应用层负责填充」这一意图，缺的是实现而不是意图。删注解要动 9 个文件，
 *       补填充器只需新增 1 个文件。</li>
 *   <li><b>不绑定 MySQL 方言</b>：{@code ON UPDATE CURRENT_TIMESTAMP} 是 MySQL 特有语法，
 *       单元测试若换 H2 等内存库就会静默失效。应用层填充与数据库无关。</li>
 *   <li><b>插入后实体在内存里就是对的</b>：下单接口会把刚插入的 {@code Order} 直接回给客户端。
 *       靠 DB 默认值的话，回包里的 {@code createTime} 仍然是 {@code null}（值只落在库里），
 *       还得多一次回查才能拿到。</li>
 *   <li><b>更新时机确定</b>：{@code ON UPDATE CURRENT_TIMESTAMP} 只在「确实有其它列发生变化」
 *       时才触发，语义不如显式填充可预期。</li>
 * </ol>
 *
 * <h3>严格按注解填充，不误伤</h3>
 * {@code Role} / {@code Permission} / {@code UserRole} 三个实体<b>同样有</b>
 * {@code createTime} / {@code updateTime} 字段，但<b>刻意没有</b>声明填充注解，
 * 它们依赖数据库默认值。本处理器只对「注解里明确声明了对应 {@link FieldFill} 的字段」
 * 动手，绝不按字段名瞎填，因此这三个实体的既有行为完全不变。
 *
 * <h3>装配方式</h3>
 * order / product / user 三个服务的启动类都带
 * {@code @ComponentScan(basePackages = "com.ecommerce")}，
 * 因此放在公共模块里的这一个 {@code @Component} 会被三者同时拾取，无需各自复制一份。
 * mobile-bff 虽然也扫描该包，但它排除了数据源、不走 MyBatis，
 * 这个 Bean 只是被创建而永远不会被回调，无副作用。
 *
 * @see com.baomidou.mybatisplus.core.handlers.MetaObjectHandler
 */
@Component
public class AuditFieldMetaObjectHandler implements MetaObjectHandler {

    /** 创建时间属性名（实体字段名，非数据库列名）。 */
    private static final String CREATE_TIME = "createTime";

    /** 更新时间属性名（实体字段名，非数据库列名）。 */
    private static final String UPDATE_TIME = "updateTime";

    /** 会在 INSERT 阶段参与填充的注解取值。 */
    private static final Set<FieldFill> INSERT_FILLS =
            Collections.unmodifiableSet(EnumSet.of(FieldFill.INSERT, FieldFill.INSERT_UPDATE));

    /** 会在 UPDATE 阶段参与填充的注解取值。 */
    private static final Set<FieldFill> UPDATE_FILLS =
            Collections.unmodifiableSet(EnumSet.of(FieldFill.UPDATE, FieldFill.INSERT_UPDATE));

    /**
     * 插入时填充创建时间与更新时间。
     *
     * <p>采用「仅当字段为 {@code null} 时填充」的策略：调用方如果出于数据迁移、
     * 补录历史单据等原因显式指定了时间，应当被尊重而不是被静默覆盖。
     *
     * @param metaObject 待填充实体的元对象
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        TableInfo tableInfo = resolveTableInfo(metaObject);
        if (tableInfo == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        fillIfDeclared(tableInfo, metaObject, CREATE_TIME, INSERT_FILLS, now, false);
        fillIfDeclared(tableInfo, metaObject, UPDATE_TIME, INSERT_FILLS, now, false);
    }

    /**
     * 更新时刷新更新时间。
     *
     * <p>这里采用<b>强制覆盖</b>而非「仅当为 null 时填充」，原因是：
     * {@code updateById(entity)} 的入参通常是刚从库里查出来的实体，
     * 它的 {@code updateTime} <b>本来就非空</b>。若沿用「为空才填」的策略，
     * MyBatis-Plus 会把这个<b>旧值</b>原样写回 {@code SET update_time = <旧值>}，
     * 结果是审计列永远停在第一次写入的时间，
     * 连数据库的 {@code ON UPDATE CURRENT_TIMESTAMP} 也一并被这个显式赋值架空。
     * 审计列的语义是「最后一次写入时间」，必须每次刷新。
     *
     * @param metaObject 待填充实体的元对象
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        TableInfo tableInfo = resolveTableInfo(metaObject);
        if (tableInfo == null) {
            return;
        }
        fillIfDeclared(tableInfo, metaObject, UPDATE_TIME, UPDATE_FILLS, LocalDateTime.now(), true);
    }

    /**
     * 解析元对象对应的 MyBatis-Plus 表信息。
     *
     * <p>更新场景下入参可能是 {@code Map}（例如只调用 {@code set()} 的
     * {@code LambdaUpdateWrapper}），此时不存在实体表信息，返回 {@code null} 由调用方跳过，
     * 避免 {@code strictInsertFill} 一类 API 在这种入参下踩空。
     *
     * @param metaObject 元对象
     * @return 表信息；非实体入参时为 {@code null}
     */
    private TableInfo resolveTableInfo(MetaObject metaObject) {
        Object original = metaObject.getOriginalObject();
        if (original == null) {
            return null;
        }
        return TableInfoHelper.getTableInfo(original.getClass());
    }

    /**
     * 当且仅当实体在该属性上声明了期望的 {@link FieldFill} 时执行填充。
     *
     * @param tableInfo  实体表信息
     * @param metaObject 元对象
     * @param property   实体属性名
     * @param expected   可接受的填充策略集合
     * @param value      待写入的值
     * @param force      {@code true} 表示无论原值是否为空都覆盖；
     *                   {@code false} 表示仅在原值为 {@code null} 时填充
     */
    private void fillIfDeclared(TableInfo tableInfo,
                                MetaObject metaObject,
                                String property,
                                Set<FieldFill> expected,
                                LocalDateTime value,
                                boolean force) {
        if (!isFillDeclared(tableInfo, property, expected)) {
            return;
        }
        if (!metaObject.hasSetter(property)) {
            return;
        }
        // 读原值前必须先确认可读：MetaObject#getValue 在缺少 getter 时会抛
        // ReflectionException，而不是返回 null。实体理应两者俱全，
        // 这里只是不让一个残缺的实体把整条写链路带崩。
        if (!force && metaObject.hasGetter(property) && metaObject.getValue(property) != null) {
            return;
        }
        metaObject.setValue(property, value);
    }

    /**
     * 判断实体是否在指定属性上声明了期望的填充策略。
     *
     * @param tableInfo 实体表信息
     * @param property  实体属性名
     * @param expected  可接受的填充策略集合
     * @return 声明了则返回 {@code true}
     */
    private boolean isFillDeclared(TableInfo tableInfo, String property, Set<FieldFill> expected) {
        if (tableInfo.getFieldList() == null) {
            return false;
        }
        for (TableFieldInfo fieldInfo : tableInfo.getFieldList()) {
            if (property.equals(fieldInfo.getProperty())) {
                return expected.contains(fieldInfo.getFieldFill());
            }
        }
        return false;
    }
}

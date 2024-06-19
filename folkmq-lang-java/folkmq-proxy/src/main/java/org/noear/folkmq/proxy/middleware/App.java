package org.noear.folkmq.proxy.middleware;

import org.noear.folkmq.proxy.middleware.admin.dso.LicenceUtils;
import org.noear.solon.Solon;
import org.noear.solon.core.event.AppLoadEndEvent;
import org.noear.solon.core.util.LogUtil;
import org.noear.solon.validation.ValidatorException;

/**
 * @author noear
 * @since 1.0
 */
public class App {
    public static void main(String[] args) {
        Solon.start(App.class, args, app -> {
            //启用安全停止
            app.cfg().stopSafe(true);

            //加载环境变量
            app.cfg().loadEnv("folkmq.");

            //打印许可证
            LicenceUtils.getGlobal().load();
            app.onEvent(AppLoadEndEvent.class, Integer.MAX_VALUE, e -> {
                LogUtil.global().info(LicenceUtils.getGlobal().getDescription());
            });

            //登录鉴权跳转
            app.routerInterceptor(0, ((ctx, mainHandler, chain) -> {
                try {
                    chain.doIntercept(ctx, mainHandler);
                } catch (ValidatorException e) {
                    ctx.redirect("/login");
                }
            }));
        });
    }
}
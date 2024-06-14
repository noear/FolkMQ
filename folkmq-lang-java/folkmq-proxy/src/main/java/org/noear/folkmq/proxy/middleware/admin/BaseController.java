package org.noear.folkmq.proxy.middleware.admin;

import org.noear.folkmq.FolkMQ;
import org.noear.solon.core.handle.ModelAndView;

/**
 * @author noear
 * @since 1.0
 */
public abstract class BaseController {

    /*
     * @return 输出一个视图（自动放置viewModel）
     * @param viewName 视图名字(内部uri)
     * */
    public ModelAndView view(String viewName) {
        //设置必要参数
        ModelAndView viewModel = new ModelAndView("/" + viewName + ".ftl");

        viewModel.put("root", "");

        viewModel.put("title", "FolkMQ-B");
        viewModel.put("app", "FolkMQ-B");
        viewModel.put("version", FolkMQ.versionName());

        viewModel.put("static", "/");
        viewModel.put("css", "/css");
        viewModel.put("js", "/js");
        viewModel.put("img", "/img");

        return viewModel;
    }
}
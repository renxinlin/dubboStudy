package org.apache.dubbo.common.extension.ext1;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;

public class SimpleExt$Adaptive implements org.apache.dubbo.common.extension.ext1.SimpleExt {
    public java.lang.String yell(URL arg0, String arg1) {
        if (arg0 == null) throw new IllegalArgumentException("url == null");
        URL url = arg0;
        String extName = url.getParameter("key1", url.getParameter("key2", "impl1"));
        if (extName == null)
            throw new IllegalStateException("Failed to get extension (org.apache.dubbo.common.extension.ext1.SimpleExt) name from url (" + url.toString() + ") use keys([key1, key2])");
        SimpleExt extension = ExtensionLoader.getExtensionLoader(SimpleExt.class).getExtension(extName);
        return extension.yell(arg0, arg1);
    }

    public String echo(URL arg0, java.lang.String arg1) {
        if (arg0 == null) throw new IllegalArgumentException("url == null");
        URL url = arg0;
        String extName = url.getParameter("simple.ext", "impl1");
        if (extName == null)
            throw new IllegalStateException("Failed to get extension (org.apache.dubbo.common.extension.ext1.SimpleExt) name from url (" + url.toString() + ") use keys([simple.ext])");
        SimpleExt extension = ExtensionLoader.getExtensionLoader(SimpleExt.class).getExtension(extName);
        return extension.echo(arg0, arg1);
    }

    public java.lang.String bang(org.apache.dubbo.common.URL arg0, int arg1) {
        throw new UnsupportedOperationException("The method public abstract java.lang.String org.apache.dubbo.common.extension.ext1.SimpleExt.bang(org.apache.dubbo.common.URL,int) of interface org.apache.dubbo.common.extension.ext1.SimpleExt is not adaptive method!");
    }
}
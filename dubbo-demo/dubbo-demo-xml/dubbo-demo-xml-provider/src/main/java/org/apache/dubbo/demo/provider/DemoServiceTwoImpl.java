package org.apache.dubbo.demo.provider;

import org.apache.dubbo.demo.DemoServiceTwo;

/**
 * @author : linjun.li@quvideo.com
 * @date : 2019-08-14 14:51
 */

public class DemoServiceTwoImpl implements DemoServiceTwo {

    @Override
    public String getName() {
        return "good";
    }
}

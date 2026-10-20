package org.tjetc.backend.service.common;

/**
 * 短信发送器（API 3.2 技术说明：验证码经短信下发）
 * 生产环境接入真实短信服务商时实现本接口并替换默认实现；
 * 发送失败抛出异常，由上层转换为 50003 第三方服务调用失败
 */
public interface SmsSender {

    /**
     * @param phone 手机号
     * @param code  6 位数字验证码
     */
    void send(String phone, String code);
}
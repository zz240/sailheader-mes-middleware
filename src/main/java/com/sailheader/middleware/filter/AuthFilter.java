package com.sailheader.middleware.filter;

import com.huaweicloud.sdk.iot.module.ItClient;
import com.huaweicloud.sdk.iot.module.exception.AuthException;
import org.apache.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;

import javax.servlet.*;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 鉴权过滤器，对来自边缘API GW的请求进行鉴权
 */
@Order(1)
@WebFilter(filterName = "authFilter", urlPatterns = "/*")
@Profile("huawei-cloud")
public class AuthFilter implements Filter {
    private final ItClient itClient;

    @Autowired
    public AuthFilter(ItClient itClient) {
        this.itClient = itClient;
    }

    @Override

    public void init(FilterConfig filterConfig) throws ServletException {

    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
        throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        try {
            itClient.verify(request.getHeader("Authorization"));
            filterChain.doFilter(servletRequest, servletResponse);
        } catch (AuthException e) {
            HttpServletResponse response = (HttpServletResponse) servletResponse;
            response.sendError(HttpStatus.SC_FORBIDDEN);
        }
        filterChain.doFilter(servletRequest, servletResponse);
    }

    @Override
    public void destroy() {

    }
}

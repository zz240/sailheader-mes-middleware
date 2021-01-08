package com.sailheader.middleware.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;

import javax.servlet.*;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Enumeration;

@WebFilter
@Slf4j
@Profile("debug-filter")
public class AllFilter implements Filter {
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {

    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
                         FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        Enumeration<String> headerNames = request.getHeaderNames();
        System.out.println("************************* print http header start *************************");
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            System.out.println(name + ":" + request.getHeader(name));
        }
        filterChain.doFilter(servletRequest, servletResponse);

        System.out.println("************************* print http header end *************************");
    }

    @Override
    public void destroy() {

    }
}

package logbook.proxy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.proxy.ProxyServlet;
import org.eclipse.jetty.util.Callback;

import logbook.Messages;

/**
 * リバースプロキシ サーブレット
 *
 */
final class ReverseProxyServlet extends ProxyServlet {

    private static final long serialVersionUID = -1;

    private static final String REQUEST_BODY = "req-body"; //$NON-NLS-1$

    private static final String RESPONSE_BODY = "res-body"; //$NON-NLS-1$

    private final List<ContentListenerSpi> services;

    public ReverseProxyServlet(List<ContentListenerSpi> services) {
        this.services = services;
    }

    @Override
    protected void sendProxyRequest(
            HttpServletRequest clientRequest,
            HttpServletResponse proxyResponse,
            Request proxyRequest) {

        RequestMetaData requestMetaData = RequestMetaDataWrapper.build(clientRequest);

        try {
            boolean require = this.services.stream()
                    .anyMatch(l -> l.test(requestMetaData));
            if (require) {
                proxyRequest.onRequestContent((r, b) -> {
                    clientRequest.setAttribute(REQUEST_BODY, Arrays.copyOf(b.array(), b.limit()));
                });
            }
        } catch (Exception e) {
            LogManager.getLogger(ReverseProxyServlet.class)
                    .warn(Messages.getString("ReverseProxyServlet.2"), e); //$NON-NLS-1$
        }
        super.sendProxyRequest(clientRequest, proxyResponse, proxyRequest);
    }

    /*
     * レスポンスが帰ってきた
     */
    @Override
    protected void onResponseContent(
            HttpServletRequest request,
            HttpServletResponse response,
            Response proxyResponse,
            byte[] buffer,
            int offset,
            int length,
            Callback callback) {

        RequestMetaData requestMetaData = RequestMetaDataWrapper.build(request);

        try {
            boolean require = this.services.stream()
                    .anyMatch(l -> l.test(requestMetaData));
            if (require) {
                ByteArrayOutputStream stream = (ByteArrayOutputStream) request.getAttribute(RESPONSE_BODY);
                if (stream == null) {
                    stream = new ByteArrayOutputStream();
                    request.setAttribute(RESPONSE_BODY, stream);
                }
                // ストリームに書き込む
                stream.write(buffer, offset, length);
            }
        } catch (Exception e) {
            LogManager.getLogger(ReverseProxyServlet.class)
                    .warn(Messages.getString("ReverseProxyServlet.3"), e); //$NON-NLS-1$
        }

        super.onResponseContent(request, response, proxyResponse, buffer, offset, length, callback);
    }

    /*
     * レスポンスが完了した
     */
    @Override
    protected void onProxyResponseSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Response proxyResponse) {

        byte[] reqBytes = (byte[]) request.getAttribute(REQUEST_BODY);
        ByteArrayOutputStream res = (ByteArrayOutputStream) request.getAttribute(RESPONSE_BODY);

        if (res != null) {
            byte[] resBytes = res.toByteArray();

            RequestMetaData requestMetaData = RequestMetaDataWrapper.build(request);
            ResponseMetaData responseMetaData = ResponseMetaDataWrapper.build(response);

            Consumer<ContentListenerSpi> consumer = l -> {
                InputStream requestIn = null;
                if (reqBytes != null) {
                    requestIn = new ByteArrayInputStream(reqBytes);
                }
                InputStream responseIn = new ByteArrayInputStream(resBytes);

                l.accept(requestMetaData, responseMetaData, requestIn, responseIn);
            };
            try {
                this.services.stream()
                        .filter(l -> l.test(requestMetaData))
                        .forEach(consumer);
            } catch (Exception e) {
                LogManager.getLogger(ReverseProxyServlet.class)
                        .warn(Messages.getString("ReverseProxyServlet.4"), e); //$NON-NLS-1$
            }
        }

        super.onProxyResponseSuccess(request, response, proxyResponse);
    }
}
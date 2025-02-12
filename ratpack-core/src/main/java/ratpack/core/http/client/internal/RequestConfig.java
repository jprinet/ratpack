/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ratpack.core.http.client.internal;

import com.google.common.base.Preconditions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.ssl.SslContext;
import io.netty.util.CharsetUtil;
import org.reactivestreams.Publisher;
import ratpack.core.http.HttpMethod;
import ratpack.core.http.MutableHeaders;
import ratpack.core.http.client.HttpClient;
import ratpack.core.http.client.ReceivedResponse;
import ratpack.core.http.client.RequestSpec;
import ratpack.core.http.internal.HttpHeaderConstants;
import ratpack.core.http.internal.NettyHeadersBackedMutableHeaders;
import ratpack.func.Action;
import ratpack.func.Function;

import javax.net.ssl.SSLParameters;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.Duration;

class RequestConfig {

  final URI uri;
  final HttpMethod method;
  final MutableHeaders headers;
  final Content content;
  final int maxContentLength;
  final Duration connectTimeout;
  final Duration readTimeout;
  final boolean decompressResponse;
  final int maxRedirects;
  final SslContext sslContext;
  final Action<? super SSLParameters> sslParams;
  final Function<? super ReceivedResponse, Action<? super RequestSpec>> onRedirect;
  final int responseMaxChunkSize;

  static RequestConfig of(URI uri, HttpClient httpClient, Action<? super RequestSpec> action) throws Exception {
    Spec spec = new Spec(uri, httpClient.getByteBufAllocator());

    spec.readTimeout = httpClient.getReadTimeout();
    spec.connectTimeout = httpClient.getConnectTimeout();
    spec.maxContentLength = httpClient.getMaxContentLength();
    spec.responseMaxChunkSize = httpClient.getMaxResponseChunkSize();

    try {
      action.execute(spec);
    } catch (Exception any) {
      if (spec.content != null) {
        spec.content.discard();
      }
      throw any;
    }

    return new RequestConfig(
      spec.uri,
      spec.method,
      spec.headers,
      spec.content,
      spec.maxContentLength,
      spec.responseMaxChunkSize,
      spec.connectTimeout,
      spec.readTimeout,
      spec.decompressResponse,
      spec.maxRedirects,
      spec.sslContext,
      spec.sslParams,
      spec.onRedirect
    );
  }

  private RequestConfig(URI uri, HttpMethod method, MutableHeaders headers, Content content, int maxContentLength, int responseMaxChunkSize, Duration connectTimeout, Duration readTimeout, boolean decompressResponse, int maxRedirects, SslContext sslContext, Action<? super SSLParameters> sslParams, Function<? super ReceivedResponse, Action<? super RequestSpec>> onRedirect) {
    this.uri = uri;
    this.method = method;
    this.headers = headers;
    this.content = content;
    this.maxContentLength = maxContentLength;
    this.responseMaxChunkSize = responseMaxChunkSize;
    this.connectTimeout = connectTimeout;
    this.readTimeout = readTimeout;
    this.decompressResponse = decompressResponse;
    this.maxRedirects = maxRedirects;
    this.sslContext = sslContext;
    this.sslParams = sslParams;
    this.onRedirect = onRedirect;
  }

  private static class Spec implements RequestSpec {

    private static final SingleBufferContent EMPTY_CONTENT = new SingleBufferContent(Unpooled.EMPTY_BUFFER);

    private final ByteBufAllocator byteBufAllocator;
    private final URI uri;

    private final MutableHeaders headers = new NettyHeadersBackedMutableHeaders(new DefaultHttpHeaders());
    private boolean decompressResponse = true;
    private Duration connectTimeout = Duration.ofSeconds(30);
    private Duration readTimeout = Duration.ofSeconds(30);
    private int maxContentLength = -1;
    private Content content = EMPTY_CONTENT;
    private HttpMethod method = HttpMethod.GET;
    private int maxRedirects = RequestSpec.DEFAULT_MAX_REDIRECTS;
    private SslContext sslContext;
    private Action<? super SSLParameters> sslParams;
    private Function<? super ReceivedResponse, Action<? super RequestSpec>> onRedirect;
    private final BodyImpl body = new BodyImpl();
    private int responseMaxChunkSize = 8192;

    Spec(URI uri, ByteBufAllocator byteBufAllocator) {
      this.uri = uri;
      this.byteBufAllocator = byteBufAllocator;
    }

    @Override
    public RequestSpec onRedirect(Function<? super ReceivedResponse, Action<? super RequestSpec>> function) {
      this.onRedirect = function;
      return this;
    }

    @Override
    public RequestSpec redirects(int maxRedirects) {
      Preconditions.checkArgument(maxRedirects >= 0);
      this.maxRedirects = maxRedirects;
      return this;
    }

    @Override
    public int getRedirects() {
      return this.maxRedirects;
    }

    @Override
    public RequestSpec sslContext(SslContext sslContext) {
      this.sslContext = sslContext;
      return this;
    }

    @Override
    public SslContext getSslContext() {
      return this.sslContext;
    }

    @Override
    public RequestSpec sslParams(Action<? super SSLParameters> sslParams) {
      this.sslParams = sslParams;
      return this;
    }

    @Override
    public Action<? super SSLParameters> getSslParams() {
      return this.sslParams;
    }

    @Override
    public MutableHeaders getHeaders() {
      return headers;
    }

    @Override
    public RequestSpec maxContentLength(int numBytes) {
      this.maxContentLength = numBytes;
      return this;
    }

    @Override
    public RequestSpec responseMaxChunkSize(int numBytes) {
      if (numBytes < 1) {
        throw new IllegalArgumentException("numBytes must be > 0");
      }
      this.responseMaxChunkSize = numBytes;
      return this;
    }

    @Override
    public int getMaxContentLength() {
      return this.maxContentLength;
    }

    @Override
    public RequestSpec headers(Action<? super MutableHeaders> action) throws Exception {
      action.execute(getHeaders());
      return this;
    }

    @Override
    public RequestSpec method(HttpMethod method) {
      this.method = method;
      return this;
    }

    @Override
    public HttpMethod getMethod() {
      return this.method;
    }

    @Override
    public URI getUri() {
      return uri;
    }

    @Override
    public RequestSpec decompressResponse(boolean shouldDecompress) {
      this.decompressResponse = shouldDecompress;
      return this;
    }

    @Override
    public boolean getDecompressResponse() {
      return this.decompressResponse;
    }

    @Override
    public RequestSpec connectTimeout(Duration duration) {
      this.connectTimeout = duration;
      return this;
    }

    @Override
    public Duration getConnectTimeout() {
      return this.connectTimeout;
    }

    @Override
    public RequestSpec readTimeout(Duration duration) {
      this.readTimeout = duration;
      return this;
    }

    @Override
    public Duration getReadTimeout() {
      return this.readTimeout;
    }

    private void setContent(Content content) {
      if (this.content != null) {
        this.content.discard();
      }
      this.content = content;
    }


    private class BodyImpl implements Body {
      @Override
      public Body type(CharSequence contentType) {
        getHeaders().set(HttpHeaderConstants.CONTENT_TYPE, contentType);
        return this;
      }

      @Override
      public Body stream(Action<? super OutputStream> action) throws Exception {
        ByteBuf byteBuf = byteBufAllocator.buffer();
        try (OutputStream outputStream = new ByteBufOutputStream(byteBuf)) {
          action.execute(outputStream);
        } catch (Throwable t) {
          byteBuf.release();
          throw t;
        }

        return buffer(byteBuf);
      }

      @Override
      public Body streamUnknownLength(Publisher<? extends ByteBuf> publisher) {
        setContent(new StreamingContent(publisher, -1));
        return this;
      }

      @Override
      public Body stream(Publisher<? extends ByteBuf> publisher, long contentLength) {
        if (contentLength < 1) {
          throw new IllegalArgumentException("contentLength must be > 0");
        }
        setContent(new StreamingContent(publisher, contentLength));
        return this;
      }

      @Override
      public Body buffer(ByteBuf byteBuf) {
        setContent(new SingleBufferContent(byteBuf));
        return this;
      }

      @Override
      public Body bytes(byte[] bytes) {
        return buffer(Unpooled.wrappedBuffer(bytes));
      }

      @Override
      public Body text(CharSequence text) {
        return text(text, CharsetUtil.UTF_8);
      }

      @Override
      public Body text(CharSequence text, Charset charset) {
        if (charset.equals(CharsetUtil.UTF_8)) {
          maybeSetContentType(HttpHeaderConstants.PLAIN_TEXT_UTF8);
        } else {
          maybeSetContentType("text/plain;charset=" + charset.name());
        }
        return buffer(Unpooled.copiedBuffer(text, charset));
      }

      private void maybeSetContentType(CharSequence s) {
        if (!headers.contains(HttpHeaderConstants.CONTENT_TYPE.toString())) {
          headers.set(HttpHeaderConstants.CONTENT_TYPE, s);
        }
      }

    }

    @Override
    public Body getBody() {
      return body;
    }

    @Override
    public RequestSpec body(Action<? super Body> action) throws Exception {
      action.execute(getBody());
      return this;
    }
  }

  interface Content {
    long getContentLength();

    boolean isBuffer();

    ByteBuf buffer();

    Publisher<? extends ByteBuf> publisher();

    void discard();
  }

  static class SingleBufferContent implements Content {
    private final ByteBuf byteBuf;

    public SingleBufferContent(ByteBuf byteBuf) {
      this.byteBuf = byteBuf;
    }

    @Override
    public long getContentLength() {
      return byteBuf.readableBytes();
    }

    @Override
    public boolean isBuffer() {
      return true;
    }

    @Override
    public ByteBuf buffer() {
      return byteBuf;
    }

    @Override
    public Publisher<? extends ByteBuf> publisher() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void discard() {
      byteBuf.release();
    }
  }

  static class StreamingContent implements Content {

    private final Publisher<? extends ByteBuf> publisher;
    private final long contentLength;

    public StreamingContent(Publisher<? extends ByteBuf> publisher, long contentLength) {
      this.publisher = publisher;
      this.contentLength = contentLength;
    }

    @Override
    public boolean isBuffer() {
      return false;
    }

    @Override
    public ByteBuf buffer() {
      throw new UnsupportedOperationException();
    }

    @Override
    public long getContentLength() {
      return contentLength;
    }

    @Override
    public Publisher<? extends ByteBuf> publisher() {
      return publisher;
    }

    @Override
    public void discard() {

    }
  }

}

package io.kroki.server.error;

import com.kitfox.svg.SVGException;
import io.kroki.server.log.Logging;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.MIMEHeader;
import io.vertx.ext.web.RoutingContext;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

public class ErrorHandler implements io.vertx.ext.web.handler.ErrorHandler {

  private static final Logger logger = LoggerFactory.getLogger(ErrorHandler.class);
  private final Logging logging;

  /**
   * Flag to enable/disable printing the full stack trace of exceptions.
   */
  private final boolean displayExceptionDetails;

  /**
   * Cached template for rendering the html errors
   */
  private final String errorTemplate;

  private final PolicyFactory htmlSanitizer;


  public ErrorHandler(Vertx vertx, boolean displayExceptionDetails) {
    this.displayExceptionDetails = displayExceptionDetails;
    String stylesheet = vertx.fileSystem().readFileBlocking("web/root/css/main.css").toString();
    String logo = vertx.fileSystem().readFileBlocking("web/root/assets/logo.svg").toString();
    this.errorTemplate = vertx.fileSystem().readFileBlocking("web/error.html").toString()
      .replace("{stylesheet}", stylesheet)
      .replace("{logo}", logo);
    this.logging = new Logging(logger);
    this.htmlSanitizer = new HtmlPolicyBuilder().toFactory();
  }

  @Override
  public void handle(RoutingContext context) {
    Throwable failure = context.failure();
    int errorCode = context.statusCode();
    final String errorMessage;
    final String statusMessage;
    String htmlErrorMessage = null;
    if (errorCode == 404) {
      statusMessage = "Not Found";
      errorMessage = statusMessage;
    } else if (failure instanceof BadRequestException) {
      errorCode = 400;
      errorMessage = failure.getMessage();
      statusMessage = "Bad Request";
      htmlErrorMessage = ((BadRequestException) failure).getMessageHTML();
    } else if (failure instanceof ServiceUnavailableException) {
      errorCode = 503;
      errorMessage = failure.getMessage();
      statusMessage = "Service Unavailable";
      htmlErrorMessage = ((ServiceUnavailableException) failure).getMessageHTML();
    } else if (failure instanceof IllegalStateException) {
      errorCode = 500;
      errorMessage = Objects.requireNonNullElse(failure.getMessage(), "Internal Server Error");
      statusMessage = "Internal Server Error";
    } else {
      if (errorCode < 400 || errorCode > 599) {
        // unexpected error code!
        logger.warn("Unexpected error code in ErrorHandler. Got: " + errorCode + ". Error code must be within 400 and 599, fallback to 500");
        errorCode = 500;
      }
      if (displayExceptionDetails) {
        errorMessage = Objects.requireNonNullElse(failure.getMessage(), "Internal Server Error");
      } else {
        errorMessage = "Internal Server Error";
      }
      statusMessage = "Internal Server Error";
    }
    handleError(new ErrorContext(context.request(), context.response(), statusMessage, new ErrorInfo(context.failure(), errorCode, errorMessage, htmlErrorMessage)));
  }

  public void handleError(ErrorContext errorContext) {
    HttpServerResponse response = errorContext.getResponse();
    response.setStatusMessage(errorContext.getStatusMessage());
    int errorCode = errorContext.getErrorCode();
    Level level = (errorCode >= 400 && errorCode <= 499) ? Level.WARN : Level.ERROR;
    logging.log(level, errorContext.getRequest(), errorContext.getErrorInfo());
    response.setStatusCode(errorCode);
    ErrorInfo errorInfo = errorContext.getErrorInfo();
    if (!sendErrorResponseMIME(response, errorInfo) && !sendErrorAcceptMIME(response, errorContext.getAcceptableMimes(), errorInfo)) {
      // fallback plain/text
      sendError(response, "text/plain", errorInfo);
    }
  }

  private boolean sendErrorResponseMIME(HttpServerResponse response, ErrorInfo errorInfo) {
    // does the response already set the mime type?
    String mime = response.headers().get(HttpHeaders.CONTENT_TYPE);
    return mime != null && sendError(response, mime, errorInfo);
  }

  private boolean sendErrorAcceptMIME(HttpServerResponse response, List<MIMEHeader> acceptableMimes, ErrorInfo errorInfo) {
    // respect the client accept order
    for (MIMEHeader accept : acceptableMimes) {
      if (sendError(response, accept.value(), errorInfo)) {
        return true;
      }
    }
    return false;
  }

  private boolean sendError(HttpServerResponse response, String mime, ErrorInfo errorInfo) {
    Throwable failure = errorInfo.getFailure();
    int errorCode = errorInfo.getCode();
    String errorMessage = errorInfo.getMessage();
    final String title = "\uD83E\uDD16 bip... bip... something wrong happened!";
    if (mime.startsWith("text/html")) {
      StringBuilder stack = new StringBuilder();
      if (failure != null && displayExceptionDetails) {
        for (StackTraceElement elem : failure.getStackTrace()) {
          stack.append("<li>");
          stack.append(htmlSanitizer.sanitize( elem.toString()));
          stack.append("</li>");
        }
      }
      response.putHeader(HttpHeaders.CONTENT_TYPE, "text/html");
      response.end(
        errorTemplate
          .replace("{title}", title)
          .replace("{errorCode}", Integer.toString(errorCode))
          .replace("{errorMessage}", htmlSanitizer.sanitize(errorInfo.getHtmlMessage()))
          .replace("{stackTrace}", stack.toString())
      );
      return true;
    }

    if (mime.startsWith("application/json")) {
      JsonObject jsonError = new JsonObject();
      jsonError.put("error", new JsonObject().put("code", errorCode).put("message", errorMessage));
      if (failure != null && displayExceptionDetails) {
        JsonArray stack = new JsonArray();
        for (StackTraceElement elem : failure.getStackTrace()) {
          stack.add(elem.toString());
        }
        jsonError.put("stack", stack);
      }
      response.putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
      response.end(jsonError.encode());
      return true;
    }

    if (mime.startsWith("text/plain")) {
      String completeErrorMessage = getCompleteErrorMessage(failure, errorCode, errorMessage);
      response.putHeader(HttpHeaders.CONTENT_TYPE, "text/plain");
      response.end(completeErrorMessage);
      return true;
    }

    if (mime.startsWith("image/svg+xml")) {
      String completeErrorMessage = getCompleteErrorMessage(failure, errorCode, errorMessage);
      try {
        String svgImage = ErrorImage.buildSVGImage(completeErrorMessage).getSource();
        response.putHeader(HttpHeaders.CONTENT_TYPE, "image/svg+xml");
        response.end(svgImage);
        return true;
      } catch (IOException | SVGException e) {
        logger.warn("Unable to generate error image", e);
        return false;
      }
    }

    if (mime.startsWith("image/png") || mime.startsWith("image/*")) {
      String completeErrorMessage = getCompleteErrorMessage(failure, errorCode, errorMessage);
      try ( ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        BufferedImage bufferedImage = ErrorImage.buildPNGImage(completeErrorMessage);
        ImageIO.write(bufferedImage, "png", output);
        response.putHeader(HttpHeaders.CONTENT_TYPE, "image/png");
        response.end(Buffer.buffer(output.toByteArray()));
        return true;
      } catch (IOException | SVGException e) {
        logger.warn("Unable to generate error image", e);
        return false;
      }
    }

    return false;
  }

  private String getCompleteErrorMessage(Throwable failure, int errorCode, String errorMessage) {
    StringBuilder sb = new StringBuilder();
    sb.append("Error ");
    sb.append(errorCode);
    sb.append(": ");
    sb.append(errorMessage);
    if (failure != null && displayExceptionDetails) {
      for (StackTraceElement elem : failure.getStackTrace()) {
        sb.append("\tat ").append(elem).append("\n");
      }
    }
    return sb.toString();
  }
}

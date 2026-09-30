package com.zaba.notez.markdown

import android.annotation.SuppressLint
import android.app.Activity
import androidx.appcompat.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.zaba.notez.R
import com.zaba.notez.ThemePref
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Local-only Markdown reading view.
 *
 * Security contract:
 * - markdown-it is loaded from APK assets and inlined into the HTML shell;
 * - no CDN/external script/style/font;
 * - raw HTML is enabled only through a small sanitized allowlist;
 * - no native JavaScript bridge;
 * - same-document #anchor links are allowed for local table-of-contents jumps;
 * - WebView navigation to remote URLs is blocked and opened externally instead;
 * - remote images are placeholders by default;
 * - user-triggered image downloads happen natively, then cached files are served back to WebView
 *   through https://notez.local/cache/image/... only.
 */
class MarkdownPreviewRenderer(
    private val activity: Activity,
    private val webView: WebView
) {
    private val markdownItJs: String
        get() = cachedMarkdownItJs ?: synchronized(MarkdownPreviewRenderer::class.java) {
            cachedMarkdownItJs ?: activity.assets.open("markdown/markdown-it.umd.min.js").bufferedReader().use { it.readText() }.also { cachedMarkdownItJs = it }
        }
    private val remoteImageCache = RemoteImageCache(activity)
    private val imageExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var destroyed = false
    private var currentMarkdown: String = ""

    init {
        configureWebView()
    }

    private var isLoaded = false

    fun render(markdown: String) {
        currentMarkdown = markdown
        if (isLoaded) {
            val jsonMarkdown = JSONObject.quote(markdown)
            val jsonBase = JSONObject.quote(githubRawBase(markdown))
            val jsonCached = cachedImagesJson(markdown)
            val js = "if (window.renderNotez) { window.renderNotez($jsonMarkdown, $jsonBase, $jsonCached); }"
            webView.evaluateJavascript(js, null)
        } else {
            webView.loadDataWithBaseURL(
                NOTEZ_BASE_URL,
                buildHtml(markdown),
                "text/html",
                "UTF-8",
                null
            )
        }
    }

    fun destroy() {
        destroyed = true
        imageExecutor.shutdownNow()
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.destroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.isVerticalScrollBarEnabled = true
        webView.isHorizontalScrollBarEnabled = false

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = false
            databaseEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            blockNetworkLoads = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url ?: return true
                if (isImageLoadRequest(uri)) {
                    confirmAndLoadImage(uri)
                    return true
                }
                if (isLocalPreviewUrl(uri)) return false
                return openExternalOrBlock(uri)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!isLoaded && url != "about:blank") {
                    isLoaded = true
                }
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val uri = request.url ?: return blockedResponse()
                if (RemoteImageCache.isCacheUri(uri)) {
                    return remoteImageCache.responseFor(uri) ?: blockedResponse()
                }
                if (isLocalPreviewUrl(uri)) return null
                if (uri.scheme.equals("about", ignoreCase = true)) return null
                return blockedResponse()
            }
        }
    }

    private fun buildHtml(markdown: String): String {
        val colors = PreviewColors.from(activity)
        // markdown quote moved inside buildHtml
        val githubRawBaseJson = JSONObject.quote(githubRawBase(markdown))
        val cachedImagesJson = cachedImagesJson(markdown)
        return """
            <!doctype html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=yes">
              <style>${css(colors)}</style>
            </head>
            <body>
              <main id="preview" class="markdown-body"></main>
              <script>$markdownItJs</script>
              <script>
                (function () {
                  'use strict';

                  function doRender(source, githubRawBase, cachedImages) {
                  var githubRawBase = $githubRawBaseJson;
                  var cachedImages = $cachedImagesJson;
                  var safeExternalLink = /^(https?:|mailto:|tel:)/i;
                  var safeAnchorLink = /^#[A-Za-z0-9][A-Za-z0-9_-]*$/;
                  var safeImageLoadLink = /^notez-image:\/\/load\?src=.+/i;
                  var safeCachedImageLink = /^https:\/\/notez\.local\/cache\/image\/[a-f0-9]{64}$/i;
                  var allowedRawHtmlTags = {
                    a: true,
                    abbr: true,
                    b: true,
                    br: true,
                    cite: true,
                    dd: true,
                    details: true,
                    div: true,
                    dl: true,
                    dt: true,
                    em: true,
                    h1: true,
                    h2: true,
                    h3: true,
                    h4: true,
                    h5: true,
                    h6: true,
                    i: true,
                    img: true,
                    kbd: true,
                    mark: true,
                    p: true,
                    s: true,
                    small: true,
                    strong: true,
                    sub: true,
                    table: true,
                    tbody: true,
                    td: true,
                    th: true,
                    thead: true,
                    tr: true,
                    summary: true,
                    sup: true,
                    u: true
                  };
                  var allowedRenderedTags = {
                    a: true,
                    abbr: true,
                    b: true,
                    blockquote: true,
                    br: true,
                    cite: true,
                    code: true,
                    dd: true,
                    del: true,
                    dl: true,
                    dt: true,
                    details: true,
                    div: true,
                    em: true,
                    h1: true,
                    h2: true,
                    h3: true,
                    h4: true,
                    h5: true,
                    h6: true,
                    hr: true,
                    i: true,
                    img: true,
                    kbd: true,
                    li: true,
                    mark: true,
                    ol: true,
                    p: true,
                    pre: true,
                    s: true,
                    small: true,
                    span: true,
                    strong: true,
                    sub: true,
                    table: true,
                    tbody: true,
                    td: true,
                    th: true,
                    thead: true,
                    tr: true,
                    summary: true,
                    sup: true,
                    u: true,
                    ul: true
                  };
                  var allowedNotezClasses = {
                    'notez-align-center': true,
                    'notez-align-justify': true,
                    'notez-align-left': true,
                    'notez-align-right': true,
                    'notez-cached-image': true,
                    'notez-image-action': true,
                    'notez-image-actions': true,
                    'notez-image-kicker': true,
                    'notez-image-load': true,
                    'notez-image-open': true,
                    'notez-image-placeholder': true,
                    'notez-image-placeholder-inline': true,
                    'notez-image-placeholder-raw': true,
                    'notez-image-placeholder-sized': true,
                    'notez-unsafe-link': true
                  };
                  var md = window.markdownit({
                    html: true,
                    linkify: true,
                    typographer: false,
                    breaks: false
                  }).enable(['table', 'strikethrough']);

                  function escapeHtml(value) {
                    return String(value || '').replace(/[&<>"']/g, function (ch) {
                      return ({
                        '&': '&amp;',
                        '<': '&lt;',
                        '>': '&gt;',
                        '"': '&quot;',
                        "'": '&#39;'
                      })[ch];
                    });
                  }

                  function escapeAttribute(value) {
                    return escapeHtml(value).replace(/`/g, '&#96;');
                  }

                  function safeClassTokens(value, tagName) {
                    return String(value || '').split(/\s+/).filter(function (name) {
                      if (!name) return false;
                      if (allowedNotezClasses[name]) return true;
                      if (tagName === 'code' && /^language-[A-Za-z0-9_.+-]{1,40}$/.test(name)) return true;
                      return false;
                    }).join(' ');
                  }

                  function isSafeTextAlign(value) {
                    return /^\s*text-align\s*:\s*(left|right|center)\s*;?\s*$/i.test(String(value || ''));
                  }

                  function normalizedTextAlign(value) {
                    var match = String(value || '').match(/text-align\s*:\s*(left|right|center)/i);
                    return match ? 'text-align:' + match[1].toLowerCase() : '';
                  }

                  function safeAlign(value) {
                    var align = String(value || '').trim().toLowerCase();
                    return /^(left|right|center|justify)$/.test(align) ? align : '';
                  }

                  function isAlignableRawTag(tagName) {
                    return tagName === 'div' || tagName === 'p' || tagName === 'td' || tagName === 'th' || /^h[1-6]$/.test(tagName);
                  }

                  function safeDimensionPx(value, max) {
                    var match = String(value || '').trim().match(/^(\d{1,4})(?:px)?$/i);
                    if (!match) return '';
                    var number = Math.max(1, Math.min(parseInt(match[1], 10), max));
                    return number + 'px';
                  }

                  function safeWidthDimension(value) {
                    var raw = String(value || '').trim();
                    var percent = raw.match(/^(\d{1,3})%$/);
                    if (percent) {
                      return Math.max(1, Math.min(parseInt(percent[1], 10), 100)) + '%';
                    }
                    return safeDimensionPx(raw, 640);
                  }

                  function imagePlaceholderStyle(widthValue, heightValue) {
                    var width = safeWidthDimension(widthValue);
                    var height = safeDimensionPx(heightValue, 480);
                    var parts = [];
                    if (width) {
                      parts.push('width:' + width);
                      parts.push('max-width:100%');
                    }
                    if (height) {
                      parts.push('min-height:' + height);
                    } else if (width) {
                      parts.push('min-height:56px');
                    }
                    return parts.join(';');
                  }

                  function normalizeImagePlaceholderStyle(value) {
                    var accepted = [];
                    String(value || '').split(';').forEach(function (part) {
                      var item = part.trim().toLowerCase();
                      var match;
                      if (!item) return;
                      match = item.match(/^width:(\d{1,4})px$/);
                      if (match) {
                        accepted.push('width:' + Math.max(1, Math.min(parseInt(match[1], 10), 640)) + 'px');
                        return;
                      }
                      match = item.match(/^width:(\d{1,3})%$/);
                      if (match) {
                        accepted.push('width:' + Math.max(1, Math.min(parseInt(match[1], 10), 100)) + '%');
                        return;
                      }
                      if (item === 'max-width:100%') {
                        accepted.push('max-width:100%');
                        return;
                      }
                      match = item.match(/^min-height:(\d{1,4})px$/);
                      if (match) {
                        accepted.push('min-height:' + Math.max(1, Math.min(parseInt(match[1], 10), 480)) + 'px');
                      }
                    });
                    return accepted.join(';');
                  }

                  function shortImageSource(value) {
                    var source = String(value || '').trim() || '(no source)';
                    return source.length > 180 ? source.slice(0, 177) + '…' : source;
                  }

                  function hasExplicitScheme(value) {
                    return /^[A-Za-z][A-Za-z0-9+.-]*:/.test(String(value || '').trim());
                  }

                  function resolvedImageSource(rawSrc) {
                    var raw = String(rawSrc || '').trim();
                    if (!raw) return '';
                    if (safeExternalHref(raw)) return raw;
                    if (raw.indexOf('//') === 0 || hasExplicitScheme(raw) || !githubRawBase) return '';
                    return githubRawBase + encodeURI(raw.replace(/^\/+/, ''));
                  }

                  function renderImageOrPlaceholder(rawSrc, alt, widthValue, heightValue, insideLink, openHref) {
                    var resolvedSrc = resolvedImageSource(rawSrc);
                    var href = safeExternalHref(resolvedSrc);
                    var safeOpenHref = safeHref(openHref || '') || href;
                    var style = imagePlaceholderStyle(widthValue, heightValue);
                    var cachedSrc = safeCachedImageHref(cachedImageHref(resolvedSrc));
                    var title = rawSrc === resolvedSrc ? shortImageSource(rawSrc) : shortImageSource(rawSrc + ' → ' + resolvedSrc);
                    var label = alt || 'image';
                    if (cachedSrc) {
                      var imgClasses = 'notez-cached-image' + (style ? ' notez-image-placeholder-sized' : '');
                      var img = '<img class="' + imgClasses + '" src="' + escapeAttribute(cachedSrc) + '" alt="' + escapeAttribute(label) + '" title="' + escapeAttribute(title) + '"' +
                        (style ? ' style="' + escapeAttribute(style) + '"' : '') + '>';
                      return safeOpenHref && !insideLink ? '<a href="' + escapeAttribute(safeOpenHref) + '" target="_self" rel="nofollow noopener noreferrer">' + img + '</a>' : img;
                    }
                    var classes = 'notez-image-placeholder notez-image-placeholder-raw notez-image-placeholder-inline' + (style ? ' notez-image-placeholder-sized' : '');
                    var helper = href ? 'Remote image belum dimuat' : 'Relative/local image placeholder';
                    var attrs = ' class="' + classes + '" title="' + escapeAttribute(title) + '"';
                    if (style) attrs += ' style="' + escapeAttribute(style) + '"';
                    var openTag = insideLink ? '<span' + attrs + '>' : '<span' + attrs + '>';
                    var actions = '';
                    if (href) {
                      actions += '<span class="notez-image-actions">' +
                        '<a class="notez-image-action notez-image-load" href="' + escapeAttribute(notezImageLoadHref(resolvedSrc)) + '">Load &amp; cache</a>';
                      if (safeOpenHref) {
                        actions += '<a class="notez-image-action notez-image-open" href="' + escapeAttribute(safeOpenHref) + '" target="_self" rel="nofollow noopener noreferrer">Open link</a>';
                      }
                      actions += '</span>';
                    }
                    return openTag +
                      '<span class="notez-image-kicker">Image</span>' +
                      '<strong>' + escapeHtml(label) + '</strong>' +
                      '<small>' + helper + '</small>' +
                      actions +
                      '</span>';
                  }

                  function soleRawImageChild(node) {
                    var image = null;
                    for (var i = 0; i < node.childNodes.length; i++) {
                      var child = node.childNodes[i];
                      if (child.nodeType === Node.TEXT_NODE && !String(child.nodeValue || '').trim()) continue;
                      if (child.nodeType === Node.ELEMENT_NODE && child.tagName.toLowerCase() === 'img' && !image) {
                        image = child;
                        continue;
                      }
                      return null;
                    }
                    return image;
                  }

                  function renderLinkedRawImage(node) {
                    var image = soleRawImageChild(node);
                    if (!image) return '';
                    return renderImageOrPlaceholder(
                      image.getAttribute('src') || '',
                      image.getAttribute('alt') || '',
                      image.getAttribute('width') || '',
                      image.getAttribute('height') || '',
                      false,
                      node.getAttribute('href') || ''
                    );
                  }

                  function renderSafeRawAttributes(node, tagName) {
                    var html = '';
                    var classes = [];
                    var align = isAlignableRawTag(tagName) ? safeAlign(node.getAttribute('align')) : '';
                    if (align) classes.push('notez-align-' + align);
                    if ((tagName === 'td' || tagName === 'th') && node.hasAttribute('colspan')) {
                      var colspan = String(node.getAttribute('colspan') || '').trim();
                      if (/^\d{1,2}$/.test(colspan)) html += ' colspan="' + Math.max(1, Math.min(parseInt(colspan, 10), 12)) + '"';
                    }
                    if ((tagName === 'td' || tagName === 'th') && node.hasAttribute('rowspan')) {
                      var rowspan = String(node.getAttribute('rowspan') || '').trim();
                      if (/^\d{1,2}$/.test(rowspan)) html += ' rowspan="' + Math.max(1, Math.min(parseInt(rowspan, 10), 12)) + '"';
                    }
                    if (tagName === 'a') {
                      var href = safeHref(node.getAttribute('href') || '');
                      if (href) {
                        html += ' href="' + escapeAttribute(href) + '" target="_self" rel="nofollow noopener noreferrer"';
                      } else if (node.hasAttribute('href')) {
                        classes.push('notez-unsafe-link');
                      }
                      if (node.hasAttribute('title')) {
                        html += ' title="' + escapeAttribute(node.getAttribute('title') || '') + '"';
                      }
                    }
                    if (tagName === 'details' && node.hasAttribute('open')) {
                      html += ' open';
                    }
                    if (tagName === 'abbr' && node.hasAttribute('title')) {
                      html += ' title="' + escapeAttribute(node.getAttribute('title') || '') + '"';
                    }
                    if (classes.length) {
                      html += ' class="' + classes.join(' ') + '"';
                    }
                    return html;
                  }

                  function renderSafeRawNodes(nodes, parentTag) {
                    var html = '';
                    Array.prototype.slice.call(nodes).forEach(function (node) {
                      html += renderSafeRawNode(node, parentTag || '');
                    });
                    return html;
                  }

                  function renderSafeRawNode(node, parentTag) {
                    if (node.nodeType === Node.TEXT_NODE) {
                      return escapeHtml(node.nodeValue || '');
                    }
                    if (node.nodeType === Node.COMMENT_NODE) {
                      return escapeHtml('<!--' + (node.nodeValue || '') + '-->');
                    }
                    if (node.nodeType !== Node.ELEMENT_NODE) {
                      return '';
                    }
                    var tagName = node.tagName.toLowerCase();
                    if (!allowedRawHtmlTags[tagName]) {
                      return escapeHtml(node.outerHTML || '');
                    }
                    if (tagName === 'br') {
                      return '<br>';
                    }
                    if (tagName === 'a') {
                      var linkedImage = renderLinkedRawImage(node);
                      if (linkedImage) return linkedImage;
                    }
                    if (tagName === 'img') {
                      return renderImageOrPlaceholder(
                        node.getAttribute('src') || '',
                        node.getAttribute('alt') || '',
                        node.getAttribute('width') || '',
                        node.getAttribute('height') || '',
                        parentTag === 'a',
                        ''
                      );
                    }
                    return '<' + tagName + renderSafeRawAttributes(node, tagName) + '>' +
                      renderSafeRawNodes(node.childNodes, tagName) +
                      '</' + tagName + '>';
                  }

                  function sanitizeRawHtml(raw) {
                    var template = document.createElement('template');
                    template.innerHTML = String(raw || '');
                    return renderSafeRawNodes(template.content.childNodes, '');
                  }

                  function isSafeRenderedElement(element, tagName) {
                    if (tagName === 'img') {
                      return hasClassToken(element, 'notez-cached-image') && !!safeCachedImageHref(element.getAttribute('src') || '');
                    }
                    if (tagName !== 'div' && tagName !== 'span') return true;
                    var classes = (element.getAttribute('class') || '').split(/\s+/).filter(Boolean);
                    if (!classes.length) return tagName === 'div';
                    return classes.every(function (name) { return !!allowedNotezClasses[name]; });
                  }

                  function hasClassToken(element, token) {
                    return (element.getAttribute('class') || '').split(/\s+/).some(function (name) {
                      return name === token;
                    });
                  }

                  function sanitizeRenderedAttributes(element, tagName) {
                    Array.prototype.slice.call(element.attributes || []).forEach(function (attr) {
                      var name = attr.name.toLowerCase();
                      var value = attr.value || '';
                      var keep = false;
                      var nextValue = value;
                      if (name === 'href' && tagName === 'a') {
                        keep = !!safeHref(value);
                      } else if (name === 'target' && tagName === 'a') {
                        keep = value === '_self';
                      } else if (name === 'rel' && tagName === 'a') {
                        keep = true;
                        nextValue = 'nofollow noopener noreferrer';
                      } else if (name === 'class') {
                        nextValue = safeClassTokens(value, tagName);
                        keep = nextValue.length > 0;
                      } else if (name === 'style' && (tagName === 'th' || tagName === 'td') && isSafeTextAlign(value)) {
                        keep = true;
                        nextValue = normalizedTextAlign(value);
                      } else if (name === 'src' && tagName === 'img') {
                        nextValue = safeCachedImageHref(value);
                        keep = nextValue.length > 0;
                      } else if ((name === 'alt') && tagName === 'img') {
                        keep = true;
                      } else if (name === 'style' && (hasClassToken(element, 'notez-image-placeholder') || hasClassToken(element, 'notez-cached-image'))) {
                        nextValue = normalizeImagePlaceholderStyle(value);
                        keep = nextValue.length > 0;
                      } else if ((name === 'colspan' || name === 'rowspan') && (tagName === 'td' || tagName === 'th') && /^\d{1,2}$/.test(value)) {
                        keep = true;
                        nextValue = String(Math.max(1, Math.min(parseInt(value, 10), 12)));
                      } else if (name === 'start' && tagName === 'ol' && /^\d{1,6}$/.test(value)) {
                        keep = true;
                      } else if (name === 'title' && (tagName === 'abbr' || tagName === 'a' || tagName === 'img' || hasClassToken(element, 'notez-image-placeholder'))) {
                        keep = true;
                      } else if (name === 'open' && tagName === 'details') {
                        keep = true;
                        nextValue = '';
                      }

                      if (keep) {
                        element.setAttribute(attr.name, nextValue);
                      } else {
                        element.removeAttribute(attr.name);
                      }
                    });
                  }

                  function replaceWithEscapedOuterHtml(node) {
                    var text = node.nodeType === Node.COMMENT_NODE
                      ? '<!--' + (node.nodeValue || '') + '-->'
                      : (node.outerHTML || node.textContent || '');
                    node.parentNode.replaceChild(document.createTextNode(text), node);
                  }

                  function sanitizeRenderedDom(root) {
                    Array.prototype.slice.call(root.childNodes).forEach(function (node) {
                      if (node.nodeType === Node.TEXT_NODE) return;
                      if (node.nodeType === Node.COMMENT_NODE) {
                        replaceWithEscapedOuterHtml(node);
                        return;
                      }
                      if (node.nodeType !== Node.ELEMENT_NODE) {
                        node.parentNode.removeChild(node);
                        return;
                      }
                      var tagName = node.tagName.toLowerCase();
                      if (!allowedRenderedTags[tagName] || !isSafeRenderedElement(node, tagName)) {
                        replaceWithEscapedOuterHtml(node);
                        return;
                      }
                      sanitizeRenderedAttributes(node, tagName);
                      sanitizeRenderedDom(node);
                    });
                  }

                  function setSafePreviewHtml(html) {
                    var template = document.createElement('template');
                    template.innerHTML = String(html || '');
                    sanitizeRenderedDom(template.content);
                    while (preview.firstChild) preview.removeChild(preview.firstChild);
                    preview.appendChild(template.content);
                  }

                  function isSafeAnchor(href) {
                    return safeAnchorLink.test(String(href || '').trim());
                  }

                  function safeExternalHref(value) {
                    var href = String(value || '').trim();
                    return safeExternalLink.test(href) ? href : '';
                  }

                  function safeImageLoadHref(value) {
                    var href = String(value || '').trim();
                    return safeImageLoadLink.test(href) ? href : '';
                  }

                  function safeCachedImageHref(value) {
                    var href = String(value || '').trim();
                    return safeCachedImageLink.test(href) ? href : '';
                  }

                  function safeHref(value) {
                    var href = String(value || '').trim();
                    if (isSafeAnchor(href)) return href;
                    if (safeImageLoadHref(href)) return href;
                    return safeExternalHref(href);
                  }

                  function notezImageLoadHref(rawSrc) {
                    return 'notez-image://load?src=' + encodeURIComponent(String(rawSrc || '').trim());
                  }

                  function cachedImageHref(rawSrc) {
                    return cachedImages[String(rawSrc || '').trim()] || '';
                  }

                  function normalizeFootnoteLabel(label) {
                    return String(label || '').trim().toLowerCase();
                  }

                  function notezFootnoteIdPart(label) {
                    var slug = slugifyHeading(normalizeFootnoteLabel(label));
                    return slug === 'section' ? 'note' : slug;
                  }

                  function notezFootnoteSlug(label) {
                    return 'notez-fn-' + notezFootnoteIdPart(label);
                  }

                  function notezFootnoteRefSlug(label, count) {
                    return 'notez-fnref-' + notezFootnoteIdPart(label) + '-' + count;
                  }

                  function notezFootnoteIndex(env, label) {
                    env.footnoteNumbers = env.footnoteNumbers || Object.create(null);
                    env.footnoteOrder = env.footnoteOrder || [];
                    if (!env.footnoteNumbers[label]) {
                      env.footnoteNumbers[label] = env.footnoteOrder.length + 1;
                      env.footnoteOrder.push(label);
                    }
                    return env.footnoteNumbers[label];
                  }

                  function markdownFenceMarker(line) {
                    var match = String(line || '').match(/^ {0,3}(`{3,}|~{3,})/);
                    return match ? match[1].charAt(0) : '';
                  }

                  function nextFenceState(line, currentFence) {
                    var marker = markdownFenceMarker(line);
                    if (!marker) return currentFence;
                    if (!currentFence) return marker;
                    return currentFence === marker ? '' : currentFence;
                  }

                  function extractFootnotes(markdown) {
                    var lines = String(markdown || '').split(/\r?\n/);
                    var output = [];
                    var definitions = Object.create(null);
                    var definitionOrder = [];
                    var fence = '';
                    for (var i = 0; i < lines.length; i++) {
                      if (fence || markdownFenceMarker(lines[i])) {
                        output.push(lines[i]);
                        fence = nextFenceState(lines[i], fence);
                        continue;
                      }
                      var match = lines[i].match(/^\[\^([^\]]+)\]:\s*(.*)$/);
                      if (!match) {
                        output.push(lines[i]);
                        continue;
                      }
                      var label = normalizeFootnoteLabel(match[1]);
                      if (!label) {
                        output.push(lines[i]);
                        continue;
                      }
                      var body = [match[2] || ''];
                      while (i + 1 < lines.length && /^(?: {4}|\t)/.test(lines[i + 1])) {
                        i += 1;
                        body.push(lines[i].replace(/^(?: {4}|\t)/, ''));
                      }
                      if (!definitions[label]) definitionOrder.push(label);
                      definitions[label] = body.join('\n').trim();
                    }
                    return {
                      markdown: output.join('\n'),
                      definitions: definitions,
                      definitionOrder: definitionOrder
                    };
                  }

                  function canStartDefinitionTerm(line) {
                    var value = String(line || '').trim();
                    if (!value) return false;
                    if (/^(?:#{1,6}\s|[-*+]\s|\d+\.\s|>|```|~~~|\||<)/.test(value)) return false;
                    if (/^\[\^([^\]]+)\]:/.test(value)) return false;
                    return true;
                  }

                  function preprocessDefinitionLists(markdown) {
                    var lines = String(markdown || '').split(/\r?\n/);
                    var output = [];
                    var fence = '';
                    for (var i = 0; i < lines.length; i++) {
                      if (fence || markdownFenceMarker(lines[i])) {
                        output.push(lines[i]);
                        fence = nextFenceState(lines[i], fence);
                        continue;
                      }
                      if (
                        i + 1 < lines.length &&
                        canStartDefinitionTerm(lines[i]) &&
                        /^:\s+/.test(lines[i + 1])
                      ) {
                        var terms = [lines[i].trim()];
                        var definitions = [];
                        i += 1;
                        while (i < lines.length && /^:\s+/.test(lines[i])) {
                          definitions.push(lines[i].replace(/^:\s+/, '').trim());
                          while (i + 1 < lines.length && /^(?: {4}|\t)/.test(lines[i + 1])) {
                            i += 1;
                            definitions[definitions.length - 1] += '\n' + lines[i].replace(/^(?: {4}|\t)/, '').trim();
                          }
                          i += 1;
                          if (i < lines.length && lines[i].trim() && !/^:\s+/.test(lines[i])) break;
                        }
                        i -= 1;
                        output.push('');
                        output.push('<dl>');
                        terms.forEach(function (term) {
                          output.push('<dt>' + escapeHtml(term) + '</dt>');
                        });
                        definitions.forEach(function (definition) {
                          output.push('<dd>' + escapeHtml(definition) + '</dd>');
                        });
                        output.push('</dl>');
                        output.push('');
                        continue;
                      }
                      output.push(lines[i]);
                    }
                    return output.join('\n');
                  }

                  function findClosingDelimiter(src, marker, start) {
                    var index = start;
                    while (index < src.length) {
                      index = src.indexOf(marker, index);
                      if (index < 0) return -1;
                      if (src.charAt(index - 1) === '\\') {
                        index += marker.length;
                        continue;
                      }
                      if (marker === '~' && (src.charAt(index - 1) === '~' || src.charAt(index + 1) === '~')) {
                        index += marker.length;
                        continue;
                      }
                      return index;
                    }
                    return -1;
                  }

                  function addSimpleDelimitedRule(ruleName, marker, tagName) {
                    md.inline.ruler.before('emphasis', ruleName, function (state, silent) {
                      var pos = state.pos;
                      var src = state.src;
                      if (src.slice(pos, pos + marker.length) !== marker) return false;
                      if (marker === '~' && src.charAt(pos + 1) === '~') return false;
                      var end = findClosingDelimiter(src, marker, pos + marker.length);
                      if (end < 0) return false;
                      var content = src.slice(pos + marker.length, end);
                      if (!content || /^\s|\s$/.test(content) || content.indexOf('\n') >= 0) return false;
                      if (silent) return false;
                      var token = state.push(ruleName, '', 0);
                      token.content = content;
                      state.pos = end + marker.length;
                      return true;
                    });
                    md.renderer.rules[ruleName] = function (tokens, idx) {
                      return '<' + tagName + '>' + escapeHtml(tokens[idx].content) + '</' + tagName + '>';
                    };
                  }

                  addSimpleDelimitedRule('notez_mark', '==', 'mark');
                  addSimpleDelimitedRule('notez_sup', '^', 'sup');
                  addSimpleDelimitedRule('notez_sub', '~', 'sub');

                  md.inline.ruler.after('escape', 'notez_footnote_ref', function (state, silent) {
                    var pos = state.pos;
                    var src = state.src;
                    if (src.charAt(pos) !== '[' || src.charAt(pos + 1) !== '^') return false;
                    var end = src.indexOf(']', pos + 2);
                    if (end < 0) return false;
                    var label = normalizeFootnoteLabel(src.slice(pos + 2, end));
                    if (!label || !state.env || !state.env.footnotes || !state.env.footnotes[label]) return false;
                    if (silent) return false;
                    var token = state.push('notez_footnote_ref', '', 0);
                    token.meta = { label: label };
                    state.pos = end + 1;
                    return true;
                  });

                  md.renderer.rules.notez_footnote_ref = function (tokens, idx, options, env) {
                    var label = tokens[idx].meta.label;
                    var index = notezFootnoteIndex(env, label);
                    env.footnoteRefCounts = env.footnoteRefCounts || Object.create(null);
                    env.footnoteRefCounts[label] = (env.footnoteRefCounts[label] || 0) + 1;
                    return '<sup><a href="#' + notezFootnoteSlug(label) + '">[' + index + ']</a></sup>';
                  };

                  var defaultLinkOpen = md.renderer.rules.link_open || function (tokens, idx, options, env, self) {
                    return self.renderToken(tokens, idx, options);
                  };

                  md.renderer.rules.link_open = function (tokens, idx, options, env, self) {
                    var href = tokens[idx].attrGet('href') || '';
                    if (!safeHref(href)) {
                      tokens[idx].attrSet('href', '#');
                      tokens[idx].attrJoin('class', 'notez-unsafe-link');
                    } else {
                      tokens[idx].attrSet('target', '_self');
                      tokens[idx].attrSet('rel', 'nofollow noopener noreferrer');
                    }
                    return defaultLinkOpen(tokens, idx, options, env, self);
                  };

                  md.renderer.rules.image = function (tokens, idx, options, env, self) {
                    var token = tokens[idx];
                    var rawSrc = token.attrGet('src') || '';
                    var alt = token.content || '';
                    if (!alt && token.children) {
                      alt = self.renderInlineAsText(token.children, options, env);
                    }
                    return renderImageOrPlaceholder(rawSrc, alt, '', '', false, '');
                  };

                  md.renderer.rules.html_inline = function (tokens, idx) {
                    return sanitizeRawHtml(tokens[idx].content || '');
                  };

                  md.renderer.rules.html_block = function (tokens, idx) {
                    return sanitizeRawHtml(tokens[idx].content || '');
                  };

                  var preview = document.getElementById('preview');
                  var footnoteExtraction = extractFootnotes(source);
                  var preparedSource = preprocessDefinitionLists(footnoteExtraction.markdown);
                  var renderEnv = {
                    footnotes: footnoteExtraction.definitions,
                    footnoteOrder: [],
                    footnoteNumbers: Object.create(null),
                    footnoteRefCounts: Object.create(null)
                  };
                  setSafePreviewHtml(md.render(preparedSource, renderEnv));
                  addHeadingAnchors();
                  wrapTables();
                  enhanceTaskLists();
                  enhanceCodeBlocks();
                  enhanceCallouts();
                  enhanceFootnoteRefs();
                  appendFootnotes(renderEnv);
                  hardenLinks();
                  addImagePlaceholderHandlers();

                  function slugifyHeading(text) {
                    var slug = String(text || '').toLowerCase()
                      .replace(/[^a-z0-9_\-\s]+/g, '')
                      .replace(/[\s\-]+/g, '-')
                      .replace(/^-+|-+$/g, '');
                    return slug || 'section';
                  }

                  function addHeadingAnchors() {
                    var seen = Object.create(null);
                    Array.prototype.slice.call(preview.querySelectorAll('h1,h2,h3,h4,h5,h6')).forEach(function (heading) {
                      var base = slugifyHeading(heading.textContent || '');
                      var count = (seen[base] || 0) + 1;
                      seen[base] = count;
                      heading.id = count === 1 ? base : base + '-' + count;
                    });
                  }

                  function wrapTables() {
                    Array.prototype.slice.call(preview.querySelectorAll('table')).forEach(function (table) {
                      if (table.parentNode && table.parentNode.classList && table.parentNode.classList.contains('notez-table-wrap')) return;
                      var wrapper = document.createElement('div');
                      wrapper.className = 'notez-table-wrap';
                      table.parentNode.insertBefore(wrapper, table);
                      wrapper.appendChild(table);
                    });
                  }

                  function enhanceTaskLists() {
                    Array.prototype.slice.call(preview.querySelectorAll('li')).forEach(function (li) {
                      var first = li.firstChild;
                      if (!first || first.nodeType !== Node.TEXT_NODE) return;
                      var match = first.nodeValue.match(/^\[( |x|X)\]\s+/);
                      if (!match) return;
                      first.nodeValue = first.nodeValue.slice(match[0].length);
                      var checkbox = document.createElement('input');
                      checkbox.type = 'checkbox';
                      checkbox.disabled = true;
                      checkbox.checked = match[1].toLowerCase() === 'x';
                      checkbox.setAttribute('aria-hidden', 'true');
                      li.classList.add('task-list-item');
                      li.insertBefore(checkbox, li.firstChild);
                    });
                  }

                  function prettyLanguageName(lang) {
                    var normalized = normalizeLanguage(lang);
                    return ({
                      js: 'JS',
                      ts: 'TS',
                      javascript: 'JS',
                      typescript: 'TS',
                      kotlin: 'KOTLIN',
                      kt: 'KOTLIN',
                      java: 'JAVA',
                      python: 'PYTHON',
                      py: 'PYTHON',
                      bash: 'SHELL',
                      sh: 'SHELL',
                      shell: 'SHELL',
                      json: 'JSON',
                      xml: 'XML',
                      html: 'HTML',
                      css: 'CSS',
                      md: 'MD',
                      markdown: 'MD'
                    })[normalized] || String(lang || 'CODE').toUpperCase();
                  }

                  function normalizeLanguage(lang) {
                    var value = String(lang || '').toLowerCase().replace(/^language-/, '');
                    if (value === 'kt') return 'kotlin';
                    if (value === 'js' || value === 'jsx') return 'javascript';
                    if (value === 'ts' || value === 'tsx') return 'typescript';
                    if (value === 'py') return 'python';
                    if (value === 'sh' || value === 'shell' || value === 'zsh') return 'bash';
                    if (value === 'htm') return 'html';
                    if (value === 'md') return 'markdown';
                    return value;
                  }

                  function languageFromCodeClass(code) {
                    var match = (code.getAttribute('class') || '').match(/(?:^|\s)language-([A-Za-z0-9_.+-]{1,40})(?:\s|$)/);
                    return match ? normalizeLanguage(match[1]) : '';
                  }

                  function syntaxSpan(className, text) {
                    return '<span class="ntz-syntax-' + className + '">' + escapeHtml(text) + '</span>';
                  }

                  function stickyRule(pattern, className) {
                    var flags = (pattern.ignoreCase ? 'i' : '') + (pattern.multiline ? 'm' : '') + 'y';
                    return { regex: new RegExp(pattern.source, flags), className: className };
                  }

                  function highlightByRules(text, rules) {
                    var sourceText = String(text || '');
                    var output = '';
                    var index = 0;
                    while (index < sourceText.length) {
                      var matched = false;
                      for (var i = 0; i < rules.length; i++) {
                        var rule = rules[i];
                        rule.regex.lastIndex = index;
                        var match = rule.regex.exec(sourceText);
                        if (match && match.index === index && match[0]) {
                          output += syntaxSpan(rule.className, match[0]);
                          index += match[0].length;
                          matched = true;
                          break;
                        }
                      }
                      if (!matched) {
                        output += escapeHtml(sourceText.charAt(index));
                        index += 1;
                      }
                    }
                    return output;
                  }

                  function codeRules(language) {
                    var commonStrings = /(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`)/;
                    var commonNumber = /\b(?:0x[0-9a-f]+|\d+(?:\.\d+)?)\b/i;
                    var cComment = /(?:\/\/[^\n]*|\/\*[\s\S]*?\*\/)/;
                    var pyComment = /#[^\n]*/;
                    var cOperator = /[{}()[\].,;:+\-*\/%=!<>|&?]+/;
                    var xmlTag = /<\/?[A-Za-z][^>]*\/?>/;
                    var rulesByLanguage = {
                      kotlin: [
                        stickyRule(cComment, 'comment'),
                        stickyRule(commonStrings, 'string'),
                        stickyRule(/\b(?:as|break|class|continue|do|else|false|for|fun|if|in|interface|is|null|object|package|return|super|this|throw|true|try|typealias|typeof|val|var|when|while|by|catch|constructor|delegate|dynamic|field|file|finally|get|import|init|param|property|receiver|set|setparam|where|actual|abstract|annotation|companion|const|crossinline|data|enum|expect|external|final|infix|inline|inner|internal|lateinit|noinline|open|operator|out|override|private|protected|public|reified|sealed|suspend|tailrec|vararg)\b/, 'keyword'),
                        stickyRule(commonNumber, 'number'),
                        stickyRule(/\b[A-Z][A-Za-z0-9_]*\b/, 'type'),
                        stickyRule(/\b[A-Za-z_][A-Za-z0-9_]*(?=\s*\()/, 'function'),
                        stickyRule(cOperator, 'operator')
                      ],
                      java: [
                        stickyRule(cComment, 'comment'),
                        stickyRule(commonStrings, 'string'),
                        stickyRule(/\b(?:abstract|assert|boolean|break|byte|case|catch|char|class|const|continue|default|do|double|else|enum|exports|extends|false|final|finally|float|for|if|implements|import|instanceof|int|interface|long|module|native|new|null|open|opens|package|private|protected|provides|public|requires|return|short|static|strictfp|super|switch|synchronized|this|throw|throws|to|transient|true|try|uses|var|void|volatile|while|with)\b/, 'keyword'),
                        stickyRule(commonNumber, 'number'),
                        stickyRule(/\b[A-Z][A-Za-z0-9_]*\b/, 'type'),
                        stickyRule(/\b[A-Za-z_][A-Za-z0-9_]*(?=\s*\()/, 'function'),
                        stickyRule(cOperator, 'operator')
                      ],
                      javascript: [
                        stickyRule(cComment, 'comment'),
                        stickyRule(commonStrings, 'string'),
                        stickyRule(/\b(?:await|async|break|case|catch|class|const|continue|debugger|default|delete|do|else|export|extends|false|finally|for|from|function|if|import|in|instanceof|let|new|null|of|return|static|super|switch|this|throw|true|try|typeof|undefined|var|void|while|yield)\b/, 'keyword'),
                        stickyRule(commonNumber, 'number'),
                        stickyRule(/\b[A-Z][A-Za-z0-9_]*\b/, 'type'),
                        stickyRule(/\b[A-Za-z_$][A-Za-z0-9_$]*(?=\s*\()/, 'function'),
                        stickyRule(cOperator, 'operator')
                      ],
                      typescript: [
                        stickyRule(cComment, 'comment'),
                        stickyRule(commonStrings, 'string'),
                        stickyRule(/\b(?:abstract|any|as|asserts|async|await|boolean|break|case|catch|class|const|continue|debugger|declare|default|delete|do|else|enum|export|extends|false|finally|for|from|function|if|implements|import|in|infer|instanceof|interface|is|keyof|let|module|namespace|never|new|null|number|object|of|private|protected|public|readonly|return|static|string|super|switch|symbol|this|throw|true|try|type|typeof|undefined|unique|unknown|var|void|while|yield)\b/, 'keyword'),
                        stickyRule(commonNumber, 'number'),
                        stickyRule(/\b[A-Z][A-Za-z0-9_]*\b/, 'type'),
                        stickyRule(/\b[A-Za-z_$][A-Za-z0-9_$]*(?=\s*\()/, 'function'),
                        stickyRule(cOperator, 'operator')
                      ],
                      python: [
                        stickyRule(pyComment, 'comment'),
                        stickyRule(commonStrings, 'string'),
                        stickyRule(/\b(?:and|as|assert|async|await|break|class|continue|def|del|elif|else|except|False|finally|for|from|global|if|import|in|is|lambda|None|nonlocal|not|or|pass|raise|return|True|try|while|with|yield)\b/, 'keyword'),
                        stickyRule(commonNumber, 'number'),
                        stickyRule(/\b[A-Z][A-Za-z0-9_]*\b/, 'type'),
                        stickyRule(/\b[A-Za-z_][A-Za-z0-9_]*(?=\s*\()/, 'function'),
                        stickyRule(cOperator, 'operator')
                      ],
                      bash: [
                        stickyRule(pyComment, 'comment'),
                        stickyRule(commonStrings, 'string'),
                        stickyRule(/\b(?:case|do|done|elif|else|esac|export|fi|for|function|if|in|local|readonly|return|select|then|until|while)\b/, 'keyword'),
                        stickyRule(/\$[A-Za-z_][A-Za-z0-9_]*|\$\{[^}]+\}/, 'variable'),
                        stickyRule(commonNumber, 'number'),
                        stickyRule(cOperator, 'operator')
                      ],
                      json: [
                        stickyRule(/"(?:\\.|[^"\\])*"(?=\s*:)/, 'key'),
                        stickyRule(/"(?:\\.|[^"\\])*"/, 'string'),
                        stickyRule(/\b(?:true|false|null)\b/, 'keyword'),
                        stickyRule(commonNumber, 'number'),
                        stickyRule(/[{}[\],:]/, 'operator')
                      ],
                      xml: [
                        stickyRule(/<!--[\s\S]*?-->/, 'comment'),
                        stickyRule(xmlTag, 'tag'),
                        stickyRule(/&[A-Za-z0-9#]+;/, 'entity')
                      ],
                      html: [
                        stickyRule(/<!--[\s\S]*?-->/, 'comment'),
                        stickyRule(xmlTag, 'tag'),
                        stickyRule(/&[A-Za-z0-9#]+;/, 'entity')
                      ],
                      css: [
                        stickyRule(/\/\*[\s\S]*?\*\//, 'comment'),
                        stickyRule(commonStrings, 'string'),
                        stickyRule(/#[0-9a-f]{3,8}\b/i, 'number'),
                        stickyRule(/\b(?:align-items|background|border|color|display|font|font-size|font-weight|gap|grid|height|justify-content|line-height|margin|padding|position|width)\b/, 'keyword'),
                        stickyRule(commonNumber, 'number'),
                        stickyRule(/[{}()[\].,;:+\-*\/%=!<>|&?]+/, 'operator')
                      ],
                      markdown: [
                        stickyRule(/^#{1,6}[^\n]*/m, 'keyword'),
                        stickyRule(/^>[^\n]*/m, 'comment'),
                        stickyRule(/^\s*(?:[-*+] |\d+\. )/m, 'operator'),
                        stickyRule(/`[^`]*`/, 'string'),
                        stickyRule(/\*\*[^*]+\*\*|__[^_]+__/, 'keyword'),
                        stickyRule(/\[[^\]]+\]\([^)]*\)/, 'function')
                      ]
                    };
                    return rulesByLanguage[language] || [];
                  }

                  function highlightCode(text, language) {
                    var rules = codeRules(language);
                    return rules.length ? highlightByRules(text, rules) : escapeHtml(text);
                  }

                  function enhanceCodeBlocks() {
                    Array.prototype.slice.call(preview.querySelectorAll('pre > code')).forEach(function (code) {
                    var language = languageFromCodeClass(code);
                    var pre = code.parentNode;
                    var rawText = code.textContent || '';
                    if (pre.parentNode && pre.parentNode.classList && pre.parentNode.classList.contains('notez-code-card')) {
                      return;
                    }
                    var card = document.createElement('div');
                    card.className = language ? 'notez-code-card notez-code-card-labeled' : 'notez-code-card';
                    if (language) {
                      var header = document.createElement('div');
                      header.className = 'notez-code-header';
                      var label = document.createElement('span');
                      label.className = 'notez-code-label';
                      label.textContent = prettyLanguageName(language);
                      header.appendChild(label);
                      card.appendChild(header);
                    }
                    pre.classList.add('notez-code-scroll');
                    pre.parentNode.insertBefore(card, pre);
                    card.appendChild(pre);
                    code.classList.add('notez-code-highlighted');
                    code.innerHTML = highlightCode(rawText, language);
                  });
                }
                  function enhanceCallouts() {
                    Array.prototype.slice.call(preview.querySelectorAll('blockquote')).forEach(function (block) {
                      var first = block.querySelector('p:first-child');
                      if (!first) return;
                      var match = (first.textContent || '').trim().match(/^\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\]/i);
                      if (!match) return;
                      var type = match[1].toLowerCase();
                      block.classList.add('notez-callout', 'notez-callout-' + type);
                      first.innerHTML = first.innerHTML.replace(/^\s*\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\]\s*(<br\s*\/?>)?\s*/i, '');
                      var title = document.createElement('div');
                      title.className = 'notez-callout-title';
                      title.textContent = match[1].toUpperCase();
                      block.insertBefore(title, block.firstChild);
                    });
                  }

                  function enhanceFootnoteRefs() {
                    var seen = Object.create(null);
                    Array.prototype.slice.call(preview.querySelectorAll('sup > a[href^="#notez-fn-"]')).forEach(function (link) {
                      var labelPart = (link.getAttribute('href') || '').replace(/^#notez-fn-/, '');
                      if (!labelPart) return;
                      seen[labelPart] = (seen[labelPart] || 0) + 1;
                      var sup = link.parentNode;
                      sup.classList.add('notez-footnote-ref');
                      sup.id = 'notez-fnref-' + labelPart + '-' + seen[labelPart];
                    });
                  }

                  function appendSanitizedInline(parent, html) {
                    var template = document.createElement('template');
                    template.innerHTML = String(html || '');
                    sanitizeRenderedDom(template.content);
                    parent.appendChild(template.content);
                  }

                  function appendFootnotes(env) {
                    if (!env || !env.footnoteOrder || !env.footnoteOrder.length) return;
                    var section = document.createElement('section');
                    section.className = 'notez-footnotes';
                    var title = document.createElement('div');
                    title.className = 'notez-footnotes-title';
                    title.textContent = 'Footnotes';
                    var list = document.createElement('ol');
                    env.footnoteOrder.forEach(function (label) {
                      var item = document.createElement('li');
                      item.id = notezFootnoteSlug(label);
                      appendSanitizedInline(item, md.renderInline(env.footnotes[label] || '', env));
                      var back = document.createElement('a');
                      back.href = '#' + notezFootnoteRefSlug(label, 1);
                      back.className = 'notez-footnote-backref';
                      back.textContent = '↩';
                      item.appendChild(document.createTextNode(' '));
                      item.appendChild(back);
                      list.appendChild(item);
                    });
                    section.appendChild(title);
                    section.appendChild(list);
                    preview.appendChild(section);
                  }

                  function hardenLinks() {
                    Array.prototype.slice.call(preview.querySelectorAll('a[href]')).forEach(function (a) {
                      var href = a.getAttribute('href') || '';
                      if (!safeHref(href)) {
                        a.removeAttribute('href');
                        a.classList.add('notez-unsafe-link');
                      }
                    });
                  }

                  function addImagePlaceholderHandlers() {
                    Array.prototype.slice.call(preview.querySelectorAll('.notez-image-placeholder')).forEach(function (box) {
                      box.addEventListener('click', function (event) {
                        if (event.target && event.target.closest && event.target.closest('a')) return;
                        var load = box.querySelector('a.notez-image-load[href]');
                        var href = load ? load.getAttribute('href') : '';
                        if (!safeImageLoadHref(href)) return;
                        event.preventDefault();
                        window.location.href = href;
                      });
                    });
                  }
                })();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun css(colors: PreviewColors): String = """
        :root {
          --notez-text: ${colors.text};
          --notez-muted: ${colors.muted};
          --notez-surface: ${colors.surface};
          --notez-accent: ${colors.accent};
          --notez-danger: ${colors.danger};
          --notez-border: rgba(255, 255, 255, 0.16);
          --notez-soft: rgba(255, 255, 255, 0.06);
        }
        html {
          scroll-behavior: smooth;
        }
        html, body {
          margin: 0;
          padding: 0;
          background: transparent;
          color: var(--notez-text);
          font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
          font-size: 16px;
          line-height: 1.55;
          overflow-x: hidden;
          overflow-wrap: anywhere;
        }
        body { padding: 0 0 24px; }
        .markdown-body > :first-child { margin-top: 0; }
        .markdown-body > :last-child { margin-bottom: 0; }
        h1, h2, h3, h4, h5, h6 {
          line-height: 1.25;
          margin: 1.25em 0 .55em;
          font-weight: 700;
          color: var(--notez-text);
          scroll-margin-top: 12px;
        }
        h1 { font-size: 1.75em; padding-bottom: .3em; border-bottom: 1px solid var(--notez-border); }
        h2 { font-size: 1.45em; padding-bottom: .25em; border-bottom: 1px solid var(--notez-border); }
        h3 { font-size: 1.2em; }
        p, ul, ol, dl, blockquote, .notez-code-card, pre, .notez-table-wrap, .notez-image-placeholder, .notez-footnotes { margin: .75em 0; }
        ul, ol { padding-left: 1.45em; }
        li + li { margin-top: .25em; }
        a { color: var(--notez-accent); text-decoration: none; }
        a:active { opacity: .75; }
        .notez-unsafe-link { color: var(--notez-muted); text-decoration: line-through; }
        .notez-align-left { text-align: left; }
        .notez-align-center { text-align: center; }
        .notez-align-right { text-align: right; }
        .notez-align-justify { text-align: justify; }
        code {
          font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace;
          font-size: .92em;
          background: var(--notez-soft);
          border-radius: 6px;
          padding: .12em .36em;
        }
        pre {
          overflow-x: auto;
          -webkit-overflow-scrolling: touch;
          background: linear-gradient(145deg, rgba(255, 255, 255, 0.075), rgba(255, 255, 255, 0.035));
          border: 1px solid var(--notez-border);
          border-radius: 14px;
          padding: 12px;
          box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.045);
        }
        .notez-code-card {
          max-width: 100%;
          overflow: hidden;
          background: linear-gradient(145deg, rgba(255, 255, 255, 0.075), rgba(255, 255, 255, 0.035));
          border: 1px solid var(--notez-border);
          border-radius: 14px;
          box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.045);
        }
        .notez-code-header {
          display: flex;
          justify-content: flex-end;
          align-items: center;
          min-height: 30px;
          padding: 8px 10px 0;
        }
        .notez-code-scroll {
          max-width: 100%;
          margin: 0;
          overflow-x: auto;
          overflow-y: hidden;
          -webkit-overflow-scrolling: touch;
          background: transparent;
          border: 0;
          border-radius: 0;
          padding: 12px;
          box-shadow: none;
        }
        .notez-code-card-labeled .notez-code-scroll {
          padding-top: 8px;
        }
        .notez-code-scroll code {
          display: inline-block;
          min-width: 100%;
          padding: 0;
          background: transparent;
          border-radius: 0;
          white-space: pre;
        }
        .notez-code-label {
          display: inline-flex;
          align-items: center;
          max-width: 42%;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
          color: var(--notez-muted);
          border: 1px solid var(--notez-border);
          border-radius: 999px;
          background: rgba(0, 0, 0, 0.18);
          padding: 2px 8px;
          font-size: .68em;
          font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace;
          font-weight: 800;
          letter-spacing: .06em;
        }
        .notez-code-highlighted .ntz-syntax-comment { color: #8B949E; font-style: italic; }
        .notez-code-highlighted .ntz-syntax-keyword { color: #FF7B72; font-weight: 700; }
        .notez-code-highlighted .ntz-syntax-string { color: #A5D6FF; }
        .notez-code-highlighted .ntz-syntax-number { color: #79C0FF; }
        .notez-code-highlighted .ntz-syntax-function { color: #D2A8FF; }
        .notez-code-highlighted .ntz-syntax-type { color: #FFA657; }
        .notez-code-highlighted .ntz-syntax-operator { color: #FF7B72; }
        .notez-code-highlighted .ntz-syntax-variable { color: #FFA657; }
        .notez-code-highlighted .ntz-syntax-key { color: #7EE787; }
        .notez-code-highlighted .ntz-syntax-tag { color: #7EE787; }
        .notez-code-highlighted .ntz-syntax-entity { color: #D2A8FF; }
        blockquote {
          border-left: 4px solid var(--notez-border);
          color: var(--notez-muted);
          padding: .08em 0 .08em 1em;
        }
        .notez-table-wrap {
          overflow-x: auto;
          -webkit-overflow-scrolling: touch;
          border: 1px solid var(--notez-border);
          border-radius: 12px;
          background: rgba(255, 255, 255, 0.025);
        }
        table {
          border-collapse: separate;
          border-spacing: 0;
          min-width: 100%;
          width: max-content;
        }
        th, td {
          border-right: 1px solid var(--notez-border);
          border-bottom: 1px solid var(--notez-border);
          padding: 8px 10px;
          text-align: left;
          vertical-align: top;
        }
        th:last-child, td:last-child { border-right: 0; }
        tr:last-child td { border-bottom: 0; }
        th {
          background: var(--notez-soft);
          font-weight: 800;
        }
        tr:nth-child(even) td { background: rgba(255, 255, 255, 0.025); }
        .task-list-item {
          list-style-type: none;
          margin-left: -1.2em;
        }
        .task-list-item input {
          margin: 0 .55em 0 0;
          transform: translateY(1px);
          accent-color: var(--notez-accent);
        }
        .notez-callout {
          border-left-width: 4px;
          border-radius: 12px;
          padding: 10px 12px;
          color: var(--notez-text);
          background: linear-gradient(145deg, rgba(255, 255, 255, 0.075), rgba(255, 255, 255, 0.032));
          box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.04);
        }
        .notez-callout > p { margin: .35em 0 0; }
        .notez-callout-title {
          font-size: .82em;
          font-weight: 800;
          letter-spacing: .04em;
          margin-bottom: .25em;
        }
        .notez-callout-note { border-left-color: #58A6FF; }
        .notez-callout-tip { border-left-color: #3FB950; }
        .notez-callout-important { border-left-color: #A371F7; }
        .notez-callout-warning { border-left-color: #D29922; }
        .notez-callout-caution { border-left-color: var(--notez-danger); }
        .notez-image-placeholder {
          display: block;
          border: 1px dashed var(--notez-border);
          border-radius: 10px;
          padding: 10px 12px;
          color: var(--notez-text);
          background: rgba(255, 255, 255, 0.035);
        }
        .notez-image-placeholder-inline {
          display: inline-flex;
          flex-direction: column;
          justify-content: center;
          vertical-align: middle;
          margin: .18em .3em .18em 0;
          box-sizing: border-box;
        }
        .notez-cached-image {
          display: inline-block;
          max-width: 100%;
          height: auto;
          vertical-align: middle;
          border-radius: 8px;
        }
        .notez-image-placeholder-sized {
          align-items: center;
          text-align: center;
        }
        .notez-image-placeholder strong,
        .notez-image-placeholder code,
        .notez-image-placeholder small {
          display: block;
          margin-top: 4px;
        }
        .notez-image-placeholder-inline strong,
        .notez-image-placeholder-inline small {
          margin-top: 2px;
        }
        .notez-image-placeholder code {
          white-space: normal;
          word-break: break-all;
        }
        .notez-image-placeholder small { color: var(--notez-muted); }
        .notez-image-placeholder-inline small { font-size: .72em; }
        .notez-image-actions {
          display: flex;
          flex-wrap: wrap;
          gap: 6px;
          justify-content: center;
          margin-top: 7px;
        }
        .notez-image-action {
          border: 1px solid var(--notez-border);
          border-radius: 999px;
          padding: 2px 8px;
          background: rgba(255, 255, 255, 0.055);
          font-size: .76em;
          font-weight: 800;
        }
        .notez-image-load { color: var(--notez-accent); }
        .notez-image-kicker {
          display: inline-block;
          color: var(--notez-muted);
          font-size: .78em;
          font-weight: 700;
          letter-spacing: .05em;
          text-transform: uppercase;
        }
        dl {
          border-left: 3px solid var(--notez-border);
          padding-left: 12px;
        }
        dt {
          color: var(--notez-text);
          font-weight: 800;
          margin-top: .55em;
        }
        dd {
          color: var(--notez-muted);
          margin: .2em 0 .55em 1em;
        }
        .notez-footnote-ref {
          font-size: .78em;
          line-height: 0;
        }
        .notez-footnote-ref a {
          border: 1px solid var(--notez-border);
          border-radius: 999px;
          padding: 0 .28em;
          background: rgba(255, 255, 255, 0.05);
        }
        .notez-footnotes {
          border-top: 1px solid var(--notez-border);
          color: var(--notez-muted);
          font-size: .9em;
          margin-top: 1.6em;
          padding-top: .9em;
        }
        .notez-footnotes-title {
          color: var(--notez-text);
          font-size: .78em;
          font-weight: 900;
          letter-spacing: .08em;
          text-transform: uppercase;
        }
        .notez-footnotes ol { padding-left: 1.4em; }
        .notez-footnotes li { margin: .35em 0; }
        .notez-footnote-backref {
          color: var(--notez-accent);
          font-size: .9em;
          text-decoration: none;
        }
        kbd {
          display: inline-block;
          border: 1px solid var(--notez-border);
          border-bottom-color: rgba(255, 255, 255, 0.26);
          border-radius: 7px;
          background: rgba(255, 255, 255, 0.08);
          color: var(--notez-text);
          box-shadow: inset 0 -1px 0 rgba(0, 0, 0, 0.22);
          font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace;
          font-size: .84em;
          padding: .08em .45em;
          white-space: nowrap;
        }
        mark {
          border-radius: 5px;
          background: rgba(255, 212, 77, 0.26);
          color: var(--notez-text);
          padding: .02em .22em;
        }
        details {
          border: 1px solid var(--notez-border);
          border-radius: 12px;
          background: rgba(255, 255, 255, 0.035);
          margin: .85em 0;
          padding: 10px 12px;
        }
        summary {
          cursor: pointer;
          color: var(--notez-text);
          font-weight: 800;
        }
        details[open] summary { margin-bottom: .45em; }
        abbr[title] {
          text-decoration: underline dotted;
          text-underline-offset: .16em;
        }
        sub, sup { line-height: 0; }
        small { color: var(--notez-muted); }
        hr {
          border: 0;
          border-top: 1px solid var(--notez-border);
          margin: 1.35em 0;
        }
    """.trimIndent()


    private fun cachedImagesJson(markdown: String): String {
        val json = JSONObject()
        extractRemoteImageSources(markdown).forEach { source ->
            remoteImageCache.cachedWebUrlFor(source)?.let { cachedUrl ->
                json.put(source, cachedUrl)
            }
        }
        return json.toString()
    }

    private fun extractRemoteImageSources(markdown: String): Set<String> {
        val sources = linkedSetOf<String>()
        val base = githubRawBase(markdown)
        MARKDOWN_IMAGE_PATTERN.findAll(markdown).forEach { match ->
            match.groupValues.getOrNull(1)
                ?.trim()
                ?.trim('<', '>')
                ?.normalizeHtmlAttribute()
                ?.resolveImageSource(base)
                ?.takeIf(::isRemoteHttpUrl)
                ?.let(sources::add)
        }
        RAW_IMG_SRC_PATTERN.findAll(markdown).forEach { match ->
            match.groupValues.getOrNull(2)
                ?.trim()
                ?.normalizeHtmlAttribute()
                ?.resolveImageSource(base)
                ?.takeIf(::isRemoteHttpUrl)
                ?.let(sources::add)
        }
        return sources
    }

    private fun githubRawBase(markdown: String): String {
        val match = GITHUB_REPO_PATTERN.find(markdown) ?: return ""
        val owner = match.groupValues[1]
        val repo = match.groupValues[2].removeSuffix(".git")
        return "https://raw.githubusercontent.com/$owner/$repo/main/"
    }

    private fun isImageLoadRequest(uri: Uri): Boolean =
        uri.scheme.equals(IMAGE_LOAD_SCHEME, ignoreCase = true) &&
            uri.host.equals(IMAGE_LOAD_HOST, ignoreCase = true) &&
            isRemoteHttpUrl(uri.getQueryParameter("src").orEmpty())

    private fun confirmAndLoadImage(uri: Uri) {
        val source = uri.getQueryParameter("src").orEmpty().trim()
        if (!isRemoteHttpUrl(source) || destroyed || activity.isFinishing) return
        val host = runCatching { Uri.parse(source).host.orEmpty() }.getOrDefault("").ifBlank { source }
        AlertDialog.Builder(activity)
            .setTitle("Load gambar online?")
            .setMessage(
                "NOTEZ akan memakai internet sekali untuk mengambil gambar dari:\n\n" +
                    "$host\n\n" +
                    "Setelah berhasil, gambar disimpan lokal dan bisa dibaca offline. " +
                    "Remote image lain tetap tidak dimuat otomatis."
            )
            .setPositiveButton("Load & cache") { _, _ -> downloadImage(source) }
            .setNegativeButton("Batal", null)
            .setNeutralButton("Open browser") { _, _ -> openExternalOrBlock(Uri.parse(source)) }
            .show()
    }

    private fun downloadImage(source: String) {
        Toast.makeText(activity, "Mengambil gambar…", Toast.LENGTH_SHORT).show()
        imageExecutor.execute {
            val result = remoteImageCache.download(source)
            activity.runOnUiThread {
                if (destroyed || activity.isFinishing) return@runOnUiThread
                Toast.makeText(activity, result.message, Toast.LENGTH_SHORT).show()
                if (result.success) render(currentMarkdown)
            }
        }
    }

    private fun isLocalPreviewUrl(uri: Uri): Boolean =
        uri.scheme.equals("https", ignoreCase = true) && uri.host.equals(NOTEZ_HOST, ignoreCase = true)

    private fun openExternalOrBlock(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return true
        if (scheme !in EXTERNAL_SCHEMES) return true
        return try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "Link tidak bisa dibuka", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "UTF-8",
        ByteArrayInputStream(ByteArray(0))
    )

    private data class PreviewColors(
        val surface: String,
        val text: String,
        val muted: String,
        val accent: String,
        val danger: String
    ) {
        companion object {
            fun from(activity: Activity): PreviewColors {
                val option = ThemePref.optionOf(ThemePref.get(activity))
                return PreviewColors(
                    surface = activity.colorResource(option.surfaceColorRes),
                    text = activity.colorResource(option.textColorRes),
                    muted = activity.colorResource(option.secondaryColorRes),
                    accent = activity.colorResource(option.accentColorRes),
                    danger = activity.colorResource(option.dangerColorRes)
                )
            }
        }
    }

    private companion object {
        @Volatile private var cachedMarkdownItJs: String? = null
        private const val NOTEZ_HOST = "notez.local"
        private const val NOTEZ_BASE_URL = "https://notez.local/"
        private const val IMAGE_LOAD_SCHEME = "notez-image"
        private const val IMAGE_LOAD_HOST = "load"
        private val EXTERNAL_SCHEMES = setOf("http", "https", "mailto", "tel")
        private val MARKDOWN_IMAGE_PATTERN = Regex("""!\[[^\]]*]\(\s*<?([^\s)>"]+)""")
        private val RAW_IMG_SRC_PATTERN = Regex("""<img\b[^>]*\bsrc\s*=\s*(["'])((?:(?!\1).)*)\1""", RegexOption.IGNORE_CASE)
        private val GITHUB_REPO_PATTERN = Regex("""https://github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(?:/|\b)""", RegexOption.IGNORE_CASE)
    }
}

private fun isRemoteHttpUrl(value: String): Boolean = runCatching {
    val uri = Uri.parse(value.trim())
    uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)
}.getOrDefault(false)

private fun String.normalizeHtmlAttribute(): String = this
    .replace("&amp;", "&")
    .replace("&quot;", "\"")
    .replace("&#34;", "\"")
    .replace("&#39;", "'")
    .replace("&apos;", "'")

private fun String.resolveImageSource(githubRawBase: String): String? {
    val source = trim()
    if (source.isBlank()) return null
    if (isRemoteHttpUrl(source)) return source
    if (source.startsWith("//") || Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(source)) return null
    if (githubRawBase.isBlank()) return null
    return githubRawBase + source.trimStart('/').split('/').joinToString("/") { Uri.encode(it) }
}

private fun Activity.colorResource(colorRes: Int): String =
    String.format(Locale.US, "#%06X", 0xFFFFFF and getColor(colorRes))

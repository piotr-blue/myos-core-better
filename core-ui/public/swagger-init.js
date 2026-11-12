window.onload = function () {
  window.ui = SwaggerUIBundle({
    url: '/api/blue/v1/openapi.json',
    dom_id: '#swagger-ui',
    deepLinking: true,
    filter: true,
    docExpansion: 'none',
    validatorUrl: null,
    supportedSubmitMethods: [],
    tryItOutEnabled: false,
    persistAuthorization: false,
    queryConfigEnabled: false,
    presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
    layout: 'BaseLayout',
  });
};

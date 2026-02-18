using System.Text.Json;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Caching.Distributed;

namespace Acme.OrdersApi;

[ApiController]
[Route("api/orders")]
public class OrdersController : ControllerBase
{
    private readonly IDistributedCache _cache;
    private readonly IOrderRepository _orders;
    private readonly IWebhookRegistry _webhooks;
    private readonly WebhookGatewayClient _gateway;

    public OrdersController(
        IDistributedCache cache,
        IOrderRepository orders,
        IWebhookRegistry webhooks,
        WebhookGatewayClient gateway)
    {
        _cache = cache;
        _orders = orders;
        _webhooks = webhooks;
        _gateway = gateway;
    }
    
    [HttpGet("{orderId:guid}")]
    public async Task<IActionResult> GetOrder([FromRoute] Guid orderId, [FromQuery] Guid tenantId)
    {

        var cacheKey = $"order:{orderId:D}";

        var cachedJson = await _cache.GetStringAsync(cacheKey);
        if (cachedJson != null)
        {

            return Content(cachedJson, "application/json");
        }

        var order = await _orders.GetForTenantAsync(tenantId, orderId);
        if (order == null) return NotFound();

        var json = JsonSerializer.Serialize(order);

        await _cache.SetStringAsync(
            cacheKey,
            json,
            new DistributedCacheEntryOptions { AbsoluteExpirationRelativeToNow = TimeSpan.FromMinutes(5) });

        return Content(json, "application/json");
    }

    [HttpPost("{orderId:guid}/test-webhook")]
    public async Task<IActionResult> TestWebhook([FromRoute] Guid orderId, [FromQuery] Guid tenantId, [FromQuery] Guid webhookId)
    {
        var endpoint = await _webhooks.GetEndpointAsync(tenantId, webhookId);
        if (endpoint == null) return NotFound();
        
        await _gateway.EnqueueTestAsync(new WebhookDispatch
        {
            TenantId = tenantId,
            OrderId = orderId,
            Target = endpoint.Url,     
            SecretRef = endpoint.SecretRef
        });

        return Accepted(new { ok = true });
    }
}

public record WebhookDispatch
{
    public Guid TenantId { get; init; }
    public Guid OrderId { get; init; }
    public string Target { get; init; } = "";
    public string SecretRef { get; init; } = "";
}

public interface IWebhookRegistry
{
    Task<WebhookEndpoint?> GetEndpointAsync(Guid tenantId, Guid webhookId);
}

public record WebhookEndpoint(Guid Id, Guid TenantId, string Url, string SecretRef);

public interface IOrderRepository
{
    Task<object?> GetForTenantAsync(Guid tenantId, Guid orderId);
}

public sealed class WebhookGatewayClient
{
    private readonly HttpClient _http;

    public WebhookGatewayClient(HttpClient http) => _http = http;

    public async Task EnqueueTestAsync(WebhookDispatch dispatch)
    {
        using var resp = await _http.PostAsJsonAsync("/dispatch/test", dispatch);
        resp.EnsureSuccessStatusCode();
    }
}

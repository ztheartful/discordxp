package com.discordxp;


import lombok.Data;

@Data
class discordWebhookBody
{
    private String content;
    private Embed embed;

    @Data
    static class Embed
    {
        final UrlEmbed image;
    }

    @Data
    static class UrlEmbed
    {
        final String url;
    }
}
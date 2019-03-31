package org.wordpress.android.fluxc.network.rest.wpcom.transactions

import org.wordpress.android.fluxc.model.DomainContactModel
import org.wordpress.android.fluxc.network.rest.wpcom.transactions.TransactionsRestClient.CreateShoppingCartResponse
import org.wordpress.android.fluxc.network.rest.wpcom.transactions.TransactionsRestClient.CreateShoppingCartResponse.Product

val SUPPORTED_COUNTRIES_MODEL = arrayOf(
        SupportedDomainCountry("US", "United State"), SupportedDomainCountry("NA", "Narnia")
)

val CREATE_SHOPPING_CART_RESPONSE = CreateShoppingCartResponse(
        76,
        22.toString(),
        listOf(
                Product(76, "superraredomainname156726.blog"),
                Product(1001, "other product")
        )
)

val DOMAIN_CONTACT_INFORMATION = DomainContactModel(
        "Wapu",
        "Wordpress",
        "WordPress",
        "7337 Publishing Row",
        "Apt 404",
        "90210",
        "Best City",
        "WP",
        "Best Country",
        "wapu@wordpress.org",
        "+1.3120000000",
        null
)
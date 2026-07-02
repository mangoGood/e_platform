package com.ecommerce.core.di

import android.content.Context
import com.ecommerce.core.datastore.TokenManager
import com.ecommerce.core.network.AddressApi
import com.ecommerce.core.network.AuthInterceptor
import com.ecommerce.core.network.CartApi
import com.ecommerce.core.network.CategoryApi
import com.ecommerce.core.network.NetworkFactory
import com.ecommerce.core.network.OrderApi
import com.ecommerce.core.network.ProductApi
import com.ecommerce.core.network.UserApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

    @Provides
    @Singleton
    fun provideTokenManager(@ApplicationContext context: Context): TokenManager =
        TokenManager(context)

    @Provides
    @Singleton
    fun provideAuthInterceptor(tokenManager: TokenManager): AuthInterceptor =
        AuthInterceptor(tokenManager)

    @Provides
    @Singleton
    fun provideOkHttpClient(authInterceptor: AuthInterceptor): OkHttpClient =
        NetworkFactory.provideOkHttpClient(authInterceptor)

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit =
        NetworkFactory.provideRetrofit(client)

    @Provides
    @Singleton
    fun provideUserApi(retrofit: Retrofit): UserApi =
        NetworkFactory.provideUserApi(retrofit)

    @Provides
    @Singleton
    fun provideProductApi(retrofit: Retrofit): ProductApi =
        NetworkFactory.provideProductApi(retrofit)

    @Provides
    @Singleton
    fun provideCategoryApi(retrofit: Retrofit): CategoryApi =
        NetworkFactory.provideCategoryApi(retrofit)

    @Provides
    @Singleton
    fun provideCartApi(retrofit: Retrofit): CartApi =
        NetworkFactory.provideCartApi(retrofit)

    @Provides
    @Singleton
    fun provideOrderApi(retrofit: Retrofit): OrderApi =
        NetworkFactory.provideOrderApi(retrofit)

    @Provides
    @Singleton
    fun provideAddressApi(retrofit: Retrofit): AddressApi =
        NetworkFactory.provideAddressApi(retrofit)
}

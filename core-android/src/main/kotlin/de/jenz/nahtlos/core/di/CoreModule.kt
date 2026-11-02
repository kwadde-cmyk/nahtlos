package de.jenz.nahtlos.core.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.jenz.nahtlos.core.db.NahtlosDatabase
import de.jenz.nahtlos.core.net.Net
import de.jenz.nahtlos.feed.ChapterJsonParser
import de.jenz.nahtlos.feed.RssFeedParser
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): NahtlosDatabase =
        Room.databaseBuilder(context, NahtlosDatabase::class.java, "nahtlos.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Provides
    @Singleton
    fun net(client: OkHttpClient): Net = Net(client)

    @Provides
    fun rssParser(): RssFeedParser = RssFeedParser()

    @Provides
    fun chapterParser(): ChapterJsonParser = ChapterJsonParser()
}

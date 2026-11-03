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
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): NahtlosDatabase =
        Room.databaseBuilder(context, NahtlosDatabase::class.java, "nahtlos.db")
            .addMigrations(NahtlosDatabase.MIGRATION_2_3, NahtlosDatabase.MIGRATION_3_4)
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
    fun rssParser(): RssFeedParser = RssFeedParser {
        android.util.Xml.newPullParser().also { parser ->
            runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) }
        }
    }

    @Provides
    fun chapterParser(): ChapterJsonParser = ChapterJsonParser()
}

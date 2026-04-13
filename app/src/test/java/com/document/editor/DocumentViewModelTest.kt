package com.document.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.document.editor.DocumentViewModel.DocType
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class DocumentViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var viewModel: DocumentViewModel
    private lateinit var context: Context

    @Mock
    private lateinit var mockContentResolver: android.content.ContentResolver

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        context = RuntimeEnvironment.application
        viewModel = DocumentViewModel(context.application)

        // Mock ContentResolver
        Mockito.`when`(context.contentResolver).thenReturn(mockContentResolver)
    }

    @Test
    fun `loadDocument sets loading state`() {
        // Given
        val uriString = "content://test/document.txt"
        val loadingLiveData = MutableLiveData<Boolean>()
        loadingLiveData.value = false

        // When
        viewModel.isLoading.observeForever(Observer { loadingLiveData.value = it })
        viewModel.loadDocument(uriString)

        // Then
        assertThat(loadingLiveData.value).isTrue()
    }

    @Test
    fun `loadDocument sets document type for text file`() {
        // Given
        val uriString = "content://test/document.txt"
        val documentTypeLiveData = MutableLiveData<DocType>()
        documentTypeLiveData.value = DocType.UNKNOWN

        // Mock ContentResolver to return text/plain MIME type
        Mockito.`when`(mockContentResolver.getType(Mockito.any(Uri::class.java)))
            .thenReturn("text/plain")
        Mockito.`when`(mockContentResolver.openFileDescriptor(Mockito.any(Uri::class.java), Mockito.anyString()))
            .thenReturn(mock(ParcelFileDescriptor::class.java))

        // When
        viewModel.documentType.observeForever(Observer { documentTypeLiveData.value = it })
        viewModel.loadDocument(uriString)

        // Then - Note: Due to threading, we need to wait for async operations
        // In a real test, we'd use IdlingResource or runBlockingTest
        // For simplicity, we're checking that the type is set correctly in the ViewModel
        assertThat(viewModel.documentType.value).isEqualTo(DocType.TEXT)
    }

    @Test
    fun `saveDocument calls content resolver`() {
        // Given
        val uriString = "content://test/document.txt"
        val testText = "Hello, World!"
        val mockOutputStream = Mockito.mock(java.io.OutputStream::class.java)

        Mockito.`when`(mockContentResolver.openOutputStream(
            Mockito.any(Uri::class.java),
            Mockito.eq("wt")
        )).thenReturn(mockOutputStream)

        // When
        viewModel.saveDocument(uriString, testText)

        // Then
        val outputStreamCaptor = ArgumentCaptor.forClass(java.io.OutputStream::class.java)
        Mockito.verify(mockContentResolver).openOutputStream(
            Mockito.argThat { uri: Uri -> uri.toString() == uriString },
            Mockito.eq("wt")
        )
        Mockito.verify(mockOutputStream).write(Mockito.eq(testText.toByteArray()))
        Mockito.verify(mockOutputStream).close()
    }
}
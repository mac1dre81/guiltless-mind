package com.document.editor

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.document.editor.DocumentViewModel.DocType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
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
import java.io.OutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], manifest = Config.NONE)
class DocumentViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var viewModel: DocumentViewModel
    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()

    @Mock
    private lateinit var mockContentResolver: ContentResolver

    @Mock
    private lateinit var mockParcelFileDescriptor: ParcelFileDescriptor

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        context = RuntimeEnvironment.getApplication().applicationContext
        viewModel = DocumentViewModel(context.applicationContext)
        
        // Set main dispatcher for coroutines
        Dispatchers.setMain(testDispatcher)
        
        // Mock ContentResolver
        Mockito.`when`(context.contentResolver).thenReturn(mockContentResolver)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadDocument sets loading state`() = runTest {
        // Given
        val uriString = "content://test/document.txt"
        val loadingStates = mutableListOf<Boolean>()
        
        // Mock the content resolver to avoid null pointer
        Mockito.`when`(mockContentResolver.getType(Mockito.any(Uri::class.java)))
            .thenReturn("text/plain")
        Mockito.`when`(mockContentResolver.openFileDescriptor(Mockito.any(Uri::class.java), Mockito.anyString()))
            .thenReturn(mockParcelFileDescriptor)

        // When
        viewModel.isLoading.observeForever { isLoading ->
            loadingStates.add(isLoading)
        }
        viewModel.loadDocument(uriString)
        
        // Advance coroutines
        testDispatcher.scheduler.advanceUntilIdle()

        // Then
        assertThat(loadingStates).isNotEmpty()
        assertThat(loadingStates.first()).isTrue()
    }

    @Test
    fun `loadDocument sets document type for text file`() = runTest {
        // Given
        val uriString = "content://test/document.txt"
        val documentTypes = mutableListOf<DocType>()

        // Mock ContentResolver to return text/plain MIME type
        Mockito.`when`(mockContentResolver.getType(Mockito.any(Uri::class.java)))
            .thenReturn("text/plain")
        Mockito.`when`(mockContentResolver.openFileDescriptor(Mockito.any(Uri::class.java), Mockito.anyString()))
            .thenReturn(mockParcelFileDescriptor)

        // When
        viewModel.documentType.observeForever { docType ->
            docType?.let { documentTypes.add(it) }
        }
        viewModel.loadDocument(uriString)
        
        // Advance coroutines
        testDispatcher.scheduler.advanceUntilIdle()

        // Then
        assertThat(documentTypes).contains(DocType.TEXT)
    }

    @Test
    fun `saveDocument writes content to output stream`() = runTest {
        // Given
        val uriString = "content://test/document.txt"
        val testText = "Hello, World!"
        val mockOutputStream = Mockito.mock(OutputStream::class.java)

        Mockito.`when`(mockContentResolver.openOutputStream(Mockito.any(Uri::class.java), Mockito.anyString()))
            .thenReturn(mockOutputStream)

        // When
        viewModel.saveDocument(uriString, testText)
        
        // Advance coroutines
        testDispatcher.scheduler.advanceUntilIdle()

        // Then
        Mockito.verify(mockContentResolver).openOutputStream(
            Mockito.argThat { uri: Uri -> uri.toString() == uriString },
            Mockito.eq("wt")
        )
        Mockito.verify(mockOutputStream).write(testText.toByteArray())
        Mockito.verify(mockOutputStream).close()
    }

    @Test
    fun `loadDocument handles unsupported file type`() = runTest {
        // Given
        val uriString = "content://test/document.xyz"
        val documentTypes = mutableListOf<DocType>()

        // Mock ContentResolver to return unknown MIME type
        Mockito.`when`(mockContentResolver.getType(Mockito.any(Uri::class.java)))
            .thenReturn("application/octet-stream")
        Mockito.`when`(mockContentResolver.openFileDescriptor(Mockito.any(Uri::class.java), Mockito.anyString()))
            .thenReturn(mockParcelFileDescriptor)

        // When
        viewModel.documentType.observeForever { docType ->
            docType?.let { documentTypes.add(it) }
        }
        viewModel.loadDocument(uriString)
        
        // Advance coroutines
        testDispatcher.scheduler.advanceUntilIdle()

        // Then
        assertThat(documentTypes).contains(DocType.UNKNOWN)
    }

    @Test
    fun `saveDocument handles empty text gracefully`() = runTest {
        // Given
        val uriString = "content://test/document.txt"
        val emptyText = ""
        val mockOutputStream = Mockito.mock(OutputStream::class.java)

        Mockito.`when`(mockContentResolver.openOutputStream(Mockito.any(Uri::class.java), Mockito.anyString()))
            .thenReturn(mockOutputStream)

        // When
        viewModel.saveDocument(uriString, emptyText)
        
        // Advance coroutines
        testDispatcher.scheduler.advanceUntilIdle()

        // Then
        Mockito.verify(mockContentResolver).openOutputStream(
            Mockito.argThat { uri: Uri -> uri.toString() == uriString },
            Mockito.eq("wt")
        )
        Mockito.verify(mockOutputStream).write(emptyText.toByteArray())
        Mockito.verify(mockOutputStream).close()
    }

    @Test
    fun `loadDocument clears loading state after completion`() = runTest {
        // Given
        val uriString = "content://test/document.txt"
        val loadingStates = mutableListOf<Boolean>()
        
        Mockito.`when`(mockContentResolver.getType(Mockito.any(Uri::class.java)))
            .thenReturn("text/plain")
        Mockito.`when`(mockContentResolver.openFileDescriptor(Mockito.any(Uri::class.java), Mockito.anyString()))
            .thenReturn(mockParcelFileDescriptor)

        // When
        viewModel.isLoading.observeForever { isLoading ->
            loadingStates.add(isLoading)
        }
        viewModel.loadDocument(uriString)
        
        // Advance coroutines
        testDispatcher.scheduler.advanceUntilIdle()

        // Then
        assertThat(loadingStates).containsExactly(true, false)
    }
}
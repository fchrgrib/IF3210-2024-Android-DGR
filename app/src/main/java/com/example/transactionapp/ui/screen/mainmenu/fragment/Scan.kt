package com.example.transactionapp.ui.screen.mainmenu.fragment

import CameraAdapter
import ImageCaptureCallback
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.example.transactionapp.R
import com.example.transactionapp.databinding.FragmentScanBinding
import com.example.transactionapp.domain.db.model.Transaction
import com.example.transactionapp.helper.getTokenSharedPref
import com.example.transactionapp.ui.screen.connection.LostConnectionActivity
import com.example.transactionapp.ui.viewmodel.auth.Auth
import com.example.transactionapp.ui.viewmodel.model.BillResponseSealed
import com.example.transactionapp.ui.viewmodel.transaction.ScanResult
import com.example.transactionapp.ui.viewmodel.transaction.TransactionViewModel
import com.example.transactionapp.utils.isInternetAvailable
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import okhttp3.RequestBody.Companion.toRequestBody

class Scan : Fragment(), ImageCaptureCallback {

    private lateinit var binding: FragmentScanBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var outputDirectory: File
    private val db: TransactionViewModel by activityViewModels()

    private lateinit var cameraExecutor: ExecutorService

    private lateinit var cameraAdapter: CameraAdapter

    private lateinit var auth: Auth

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        binding = FragmentScanBinding.inflate(layoutInflater)
        val billList = mutableListOf<Transaction>()

        outputDirectory = getOutputDirectory()
        cameraExecutor = Executors.newSingleThreadExecutor()

        auth = ViewModelProvider(requireActivity())[Auth::class.java]

        cameraAdapter = CameraAdapter( requireContext(), viewLifecycleOwner, binding.previewView, this)
        cameraAdapter.startCamera()

        binding.cameraButton.setOnClickListener {
            takePhoto()
        }

        binding.galleryButton.setOnClickListener {
            pickFromGallery()
        }

        binding.retakeButton.setOnClickListener {
            binding.overlay.setImageBitmap(null)
            binding.retakeButton.visibility = View.GONE
            binding.confirmButton.visibility = View.GONE
            binding.cameraButton.visibility = View.VISIBLE
            binding.galleryButton.visibility = View.VISIBLE
        }

        binding.confirmButton.setOnClickListener {
            val bitmap = (binding.overlay.drawable as? BitmapDrawable)?.bitmap
            if (!isInternetAvailable(requireContext())) {
                val intent = Intent(requireContext(), LostConnectionActivity::class.java)
                startActivity(intent)
                return@setOnClickListener
            }
            if (bitmap != null) {
                sendBillToServer(bitmap)
            } else {
                Toast.makeText(requireContext(), "No image to confirm", Toast.LENGTH_SHORT).show()
            }
        }

        auth.billResponse.observe(requireActivity(), Observer {billValue ->
            when (val data = billValue) {
                is BillResponseSealed.Success -> {
                    var title = ""
                    var nominal = 0L
                    var count = 0
                    data.data.items.items.forEach {
                        if (count < 1) {
                            title += it.name + ", "
                        } else {
                        }
                        nominal += it.price.toLong() * 12000L * it.qty
                        count++
                    }
                    if (count > 1) {
                        title += "etc."
                    } else {
                        title = title.dropLast(2)
                    }

                    val args = Bundle()
                    args.putString(TransactionForm.ARG_ITEM_NAME, title)
                    args.putLong(TransactionForm.ARG_ITEM_NOMINAL, nominal)

                    val transactionFormFragment = TransactionForm()
                    transactionFormFragment.arguments = args

                    db.setAtomicTransaction(
                        ScanResult(
                            title = title,
                            nominal = nominal
                        )
                    )

                    db.changeCameraStatus(true)
                    auth.resetBillResponse()
                }
                is BillResponseSealed.Error -> {
                    auth.resetBillResponse()
                    Toast.makeText(requireContext(), data.message, Toast.LENGTH_SHORT).show()
                }
                else -> {}
            }
        })

        return binding.root
    }

    override fun onImageCaptureInitialized(imageCapture: ImageCapture) {
        this.imageCapture = imageCapture
    }

    private fun pickFromGallery() {
        val intent = Intent(Intent.ACTION_PICK)
        intent.type = "image/*"
        startActivityForResult(intent, 100)
    }

    override fun onResume() {
        super.onResume()
        // Set the orientation to portrait
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == Activity.RESULT_OK) {
            val selectedImageUri: Uri? = data?.data
            selectedImageUri?.let {
                try {
                    val bitmap = MediaStore.Images.Media.getBitmap(requireActivity().contentResolver, selectedImageUri)
                    val exif = ExifInterface(requireActivity().contentResolver.openInputStream(selectedImageUri)!!)
                    val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    val rotatedBitmap = rotateBitmap(bitmap, orientation)

                    binding.overlay.setImageBitmap(rotatedBitmap)
                    binding.retakeButton.visibility = View.VISIBLE
                    binding.confirmButton.visibility = View.VISIBLE
                    binding.cameraButton.visibility = View.GONE
                    binding.galleryButton.visibility = View.GONE

                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(requireContext(), "Failed to load image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun sendBillToServer(bitmap: Bitmap) {
        val requestFile = bitmap.toString().toRequestBody("image/jpg".toMediaTypeOrNull())
        val body = MultipartBody.Part.createFormData("file", "image.jpg", requestFile)
        val token = getTokenSharedPref(requireContext())

        auth.postBill("Bearer $token", body)
    }


    private fun getOutputDirectory(): File {
        val mediaDir = requireContext().externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else requireContext().filesDir
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val photoFile = File(
            outputDirectory, System.currentTimeMillis().toString() + ".jpg"
        )
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions, ContextCompat.getMainExecutor(requireContext()), object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(requireContext(), "Error", Toast.LENGTH_SHORT).show()
                }
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                    val exif = ExifInterface(photoFile.absolutePath)
                    val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    val rotatedBitmap = rotateBitmap(bitmap, orientation)
                    binding.overlay.setImageBitmap(rotatedBitmap)

                    binding.retakeButton.visibility = View.VISIBLE
                    binding.confirmButton.visibility = View.VISIBLE
                    binding.cameraButton.visibility = View.GONE
                    binding.galleryButton.visibility = View.GONE
                }
            }
        )
    }

    private fun rotateBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // TODO: Fix navigate to scan while in landscape causing app to crash
//    override fun onDestroy() {
//        super.onDestroy()
//        cameraExecutor.shutdown()
//    }
}
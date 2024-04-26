package com.mongodb.tasktracker

import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.mongodb.tasktracker.databinding.ActivityHomeBinding
import com.mongodb.tasktracker.model.CourseInfo
import com.mongodb.tasktracker.model.SlotInfo
import com.mongodb.tasktracker.model.TermInfo
import io.realm.Realm
import io.realm.mongodb.App
import io.realm.mongodb.AppConfiguration
import org.bson.Document
import org.bson.types.ObjectId
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.location.Location
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import com.mongodb.tasktracker.model.SharedViewModel
import java.math.BigDecimal
import java.math.RoundingMode

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    lateinit var app: App

    private var studentName: String? = null
    private var studentEmail: String? = null
    private var departmentName: String? = null
    private var studentId: ObjectId? = null
    private var blockchainPP: Double? = null
    private var blockchainLDTs: Double? = null
    private var addressWallet: String? = null


    private var coursesInfo: List<CourseInfo>? = null
    private var slotsData: List<SlotInfo>? = null
    private var courseTitlesMap = mutableMapOf<String, String>()

    lateinit var viewModel: SharedViewModel

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider(this).get(SharedViewModel::class.java)

        // Started Realm
        Realm.init(this)
        val appConfiguration = AppConfiguration.Builder("finalproject-rujev").build()
        app = App(appConfiguration)  // Started app

        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Receive email from Intent and get data queries
        intent.getStringExtra("USER_EMAIL")?.let {
            fetchStudentData(it)
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        binding.bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.userInterface -> replaceFragment(InterfaceFragment())
                R.id.user -> replaceFragment(InforFragment())
                R.id.gear -> replaceFragment(GearFragment())
                /*R.id.shop -> replaceFragment(ShopFragment())*/
                else -> false
            }
            true
        }

        if (intent.getBooleanExtra("SHOW_INFOR_FRAGMENT", false)) {
            replaceFragment(InforFragment())
        } else {
            replaceFragment(InterfaceFragment())
        }
    }

    //fetch Student from data
    private fun fetchStudentData(userEmail: String) {
        val mongoClient = app.currentUser()?.getMongoClient("mongodb-atlas")
        val database = mongoClient?.getDatabase("finalProject")
        Log.d("fetchStudentData", "Attempting to fetch student data for email: $userEmail")

        database?.getCollection("Students")?.findOne(Document("email", userEmail))?.getAsync { task ->
            if (task.isSuccess) {
                val studentDocument = task.get()
                Log.d("fetchStudentData", "Successfully fetched student data: ${studentDocument?.toJson()}")

                studentName = studentDocument?.getString("name")
                this.studentEmail = studentDocument?.getString("email")
                this.studentId = studentDocument?.getObjectId("_id")

                this.studentId?.let {
                    fetchBlockchainData(it)
                }

                val departmentId = studentDocument?.getObjectId("departmentId")

                if (departmentId != null) {
                    Log.d("fetchStudentData", "Fetching department data for ID: $departmentId")
                    fetchDepartmentData(departmentId)
                }

                // Lấy danh sách khóa học mà sinh viên đã đăng ký
                val enrolledCourses = studentDocument?.getList("enrolledCourses", ObjectId::class.java)
                if (!enrolledCourses.isNullOrEmpty()) {
                    Log.d("fetchStudentData", "Fetching courses data for enrolled courses: $enrolledCourses")
                    fetchCoursesData(enrolledCourses) {
                        // Dựa vào danh sách khóa học để lấy thông tin về term và slots
                        fetchCurrentTermAndSlots(enrolledCourses)
                    }
                } else {
                    Log.e("fetchStudentData", "No enrolled courses found for user: $userEmail")
                }
            } else {
                Log.e("fetchStudentData", "Error fetching student data: ${task.error}")
            }
        }
    }

    //fetch Department from data
    private fun fetchDepartmentData(departmentId: ObjectId) {
        val mongoClient = app.currentUser()!!.getMongoClient("mongodb-atlas")
        val database = mongoClient.getDatabase("finalProject")
        val departmentsCollection = database.getCollection("Departments")

        val query = Document("_id", departmentId)
        Log.d("fetchDepartmentData", "Querying for department with ID: $departmentId")

        departmentsCollection.findOne(query).getAsync { task ->
            if (task.isSuccess) {
                val departmentDocument = task.get()
                if (departmentDocument != null) {
                    departmentName = departmentDocument.getString("name")
                    Log.d("fetchDepartmentData", "Successfully fetched department: ${departmentDocument.toJson()}")
                } else {
                    Log.e("fetchDepartmentData", "Cannot find department with ID: $departmentId")
                }
            } else {
                Log.e("fetchDepartmentData", "Error to query: ${task.error}")
            }
        }
    }

    //fetch Courses from data
    private fun fetchCoursesData(courseIds: List<ObjectId>, onComplete: () -> Unit) {
        val mongoClient = app.currentUser()?.getMongoClient("mongodb-atlas")
        val database = mongoClient?.getDatabase("finalProject")
        val coursesCollection = database?.getCollection("Courses")
        val departmentsCollection = database?.getCollection("Departments")

        val coursesInfoTemp = mutableListOf<CourseInfo>()
        var completedCount = 0  // Count variable to track the number of courses processed

        courseIds.forEach { courseId ->
            coursesCollection?.findOne(Document("_id", courseId))?.getAsync { task ->
                if (task.isSuccess) {
                    val courseDocument = task.get()
                    val title = courseDocument?.getString("title") ?: "Unknown"
                    val description = courseDocument?.getString("description") ?: "No description"
                    val departmentId = courseDocument?.getObjectId("departmentId")
                    val credits = courseDocument?.getInteger("credits", 0) ?: 0

                    courseTitlesMap[courseId.toString()] = title

                    if (departmentId != null) {
                        departmentsCollection?.findOne(Document("_id", departmentId))?.getAsync { deptTask ->
                            if (deptTask.isSuccess) {
                                val departmentDocument = deptTask.get()
                                val departmentName = departmentDocument?.getString("name") ?: "Unknown department"

                                coursesInfoTemp.add(CourseInfo(title, description, departmentName, credits))
                            } else {
                                Log.e("fetchCoursesData", "Error fetching department data: ${deptTask.error}")
                            }

                            // Check all tasks have been completed or not
                            if (++completedCount == courseIds.size) {
                                coursesInfo = coursesInfoTemp
                                onComplete()  // call callback when all courses have been processed
                            }
                        }
                    } else {
                        Log.e("fetchCoursesData", "Department ID not found for course: $title")

                        // Continue checking if there is no departmentId
                        if (++completedCount == courseIds.size) {
                            coursesInfo = coursesInfoTemp
                            onComplete()  // call callback when all courses have been processed
                        }
                    }
                } else {
                    Log.e("fetchCoursesData", "Error fetching course data: ${task.error}")
                }
            }
        }
    }

    //Query Collection BlockChains to get pp & ldts
    private fun fetchBlockchainData(studentId: ObjectId) {
        Log.d("fetchBlockchainData", "Fetching blockchain data for studentId: $studentId")

        val mongoClient = app.currentUser()?.getMongoClient("mongodb-atlas")
        val database = mongoClient?.getDatabase("finalProject")
        val blockChainsCollection = database?.getCollection("BlockChains")

        val query = Document("studentId", studentId)
        Log.d("fetchBlockchainData", "Query used: $query")

        blockChainsCollection?.findOne(query)?.getAsync { task ->
            if (task.isSuccess) {
                val blockchainDocument = task.get()
                Log.d("fetchBlockchainData", "Blockchain data fetched successfully")

                val pp = blockchainDocument?.get("PP")?.let {
                    when (it) {
                        is Number -> BigDecimal(it.toDouble()).setScale(2, RoundingMode.HALF_EVEN).toDouble()
                        else -> 0.0
                    }
                } ?: 0.0
                val lDTs = blockchainDocument?.get("LDTs")?.let {
                    when (it) {
                        is Number -> BigDecimal(it.toDouble()).setScale(2, RoundingMode.HALF_EVEN).toDouble()
                        else -> 0.0
                    }
                } ?: 0.0
                addressWallet = blockchainDocument?.getString("addressWallet") ?: "No address"

                blockchainPP = pp
                blockchainLDTs = lDTs
                Log.d("fetchBlockchainData", "PP: $pp, LDTs: $lDTs")

                // Update UI
                runOnUiThread {
                    updateBlockchainUI(pp, lDTs)
                    sendBlockchainDataToInterfaceFragment()
                }
            } else {
                Log.e("fetchBlockchainData", "Error fetching blockchain data: ${task.error}")
            }
        }
    }

    private fun updateBlockchainUI(pp: Double, ldts: Double) {
        val inforFragment = supportFragmentManager.findFragmentByTag("InforFragment") as? InforFragment
        if (inforFragment != null && inforFragment.isAdded && inforFragment.view != null) {
            inforFragment.view?.findViewById<TextView>(R.id.pp_number)?.text = "PP: $pp"
            inforFragment.view?.findViewById<TextView>(R.id.lDTs_number)?.text = "LDTs: $ldts"
        } else {
            Log.e("updateBlockchainUI", "Fragment not added or view not created")
        }
    }

    private fun sendBlockchainDataToInterfaceFragment() {
        val interfaceFragment = supportFragmentManager.findFragmentByTag("InterfaceFragment") as? InterfaceFragment
        interfaceFragment?.let {
            val args = Bundle().apply {
                putString("addressWallet", addressWallet)
            }
            it.arguments = args
            supportFragmentManager.beginTransaction().replace(R.id.frame_layout, it).commit()
        }
    }

    private fun checkAndPassCourses(coursesInfo: List<CourseInfo>, totalCourses: Int) {
        if (coursesInfo.size == totalCourses) {
            passCoursesToFragment(coursesInfo)
        }
    }

    private fun passCoursesToFragment(coursesInfo: List<CourseInfo>) {
        this.coursesInfo = coursesInfo
        // Update InforFragment with new data
        val inforFragment = supportFragmentManager.findFragmentByTag("InforFragment") as? InforFragment
        inforFragment?.let { fragment ->

            val bundle = Bundle().apply {
                putString("name", studentName ?: "N/A")
                putString("email", studentEmail ?: "N/A")
                putString("department", departmentName ?: "N/A")
                putSerializable("courses", ArrayList(coursesInfo))
                // Thêm thông tin blockchain vào bundle
                putDouble("PP", blockchainPP ?: 0.0)
                putDouble("LDTs", blockchainLDTs ?: 0.0)
            }
            fragment.arguments = bundle
            replaceFragment(fragment)
        }
    }

    private fun fetchCurrentTermAndSlots(courseIds: List<ObjectId>) {
        val mongoClient = app.currentUser()?.getMongoClient("mongodb-atlas")
        val database = mongoClient?.getDatabase("finalProject")
        val termsCollection = database?.getCollection("Terms")

        val currentDate = Date()
        // Lấy term hiện tại dựa trên ngày hiện tại mà không phụ thuộc vào departmentId
        val query = Document("\$and", listOf(
            Document("startDate", Document("\$lte", currentDate)),
            Document("endDate", Document("\$gte", currentDate))
        ))

        termsCollection?.findOne(query)?.getAsync { task ->
            if (task.isSuccess) {
                val termDocument = task.get()
                Log.d("fetchCurrentTerm", "Current term found: ${termDocument?.toJson()}")

                val termId = termDocument?.getObjectId("_id")
                if (termId != null) {
                    fetchCoursesAndSlots(courseIds, termId)
                } else {
                    Log.e("fetchCurrentTerm", "Term ID is null after querying current term.")
                }
            } else {
                Log.e("fetchCurrentTerm", "Error fetching current term: ${task.error}")
            }
        }
    }

    private fun fetchCoursesAndSlots(courseIds: List<ObjectId>, termId: ObjectId) {
        val mongoClient = app.currentUser()?.getMongoClient("mongodb-atlas")
        val database = mongoClient?.getDatabase("finalProject")

        Log.d("fetchCoursesAndSlots", "Start fetching slots with termId: $termId and courseIds: $courseIds")
        val slotsCollection = database?.getCollection("Slots")

        Log.d("fetchCoursesAndSlots", "Fetching slots for termId: $termId and courseIds: $courseIds")

        //Query Slots based on courseId and termId
        val query = Document("\$and", listOf(
            Document("courseId", Document("\$in", courseIds)),
            Document("termId", termId)
        ))

        slotsCollection?.find(query)?.iterator()?.getAsync { task ->
            if (task.isSuccess) {
                val documents = task.get() // Get query results
                val slots = mutableListOf<SlotInfo>() // Initialize slots list

                documents.forEach { document ->
                    //Process each document here
                    val slotId = document.getObjectId("_id").toString()
                    val courseId = document.getObjectId("courseId").toString()

                    Log.d("SlotDetail", "Processing slot $slotId for courseId $courseId")
                    //Check if courseId exists in courseTitlesMap
                    if (courseTitlesMap.containsKey(courseId)) {

                        val courseTitle = courseTitlesMap[courseId]
                        Log.d("SlotDetail", "Found title for courseId $courseId: $courseTitle")
                    } else {
                        Log.d("SlotDetail", "No title found for courseId $courseId in courseTitlesMap")
                    }

                    val slotInfo = SlotInfo(
                        slotId = slotId,
                        startTime = document.getString("startTime"),
                        endTime = document.getString("endTime"),
                        day = document.getString("day"),
                        courseId = courseId,
                        courseTitle = courseTitlesMap[courseId] ?: "Title not found", // update courseTitle from map
                        building = "Pending" // Will update after getting building information
                    )
                    slots.add(slotInfo) // Add slotInfo to the list
                }

                Log.d("fetchCoursesAndSlots", "Fetched slots: ${slots.size}")

                // Continue with getting building information
                fetchAttendanceRecordsForSlots(slots, termId) { slotsWithAttendance ->
                    // Add building information for each slot that has attendance information
                    fetchBuildingForSlots(slotsWithAttendance, termId) { fullyUpdatedSlots ->
                        slotsData = fullyUpdatedSlots
                        runOnUiThread {
                            // Send updated data to UI
                            sendSlotsDataToInterfaceFragment(fullyUpdatedSlots)
                        }
                    }
                }
            } else {
                Log.e("fetchCoursesAndSlots", "Error fetching slots: ${task.error}")
            }
        }
    }

    private fun fetchBuildingForSlots(slots: List<SlotInfo>, termId: ObjectId, completion: (List<SlotInfo>) -> Unit) {
        val mongoClient = app.currentUser()?.getMongoClient("mongodb-atlas")
        val database = mongoClient?.getDatabase("finalProject")
        val roomsCollection = database?.getCollection("Rooms")

        val updatedSlots = mutableListOf<SlotInfo>()
        var callbackCount = 0

        slots.forEach { slot ->
            // The query will include both slotId and termId
            val query = Document("\$and", listOf(
                Document("availability", Document("\$elemMatch", Document("slotId", Document("\$oid", slot.slotId)).append("termId", termId)))
            ))

            roomsCollection?.findOne(query)?.getAsync { task ->
                if (task.isSuccess) {
                    val roomDocument = task.get()
                    val building = roomDocument?.getString("building") ?: "Unknown"
                    updatedSlots.add(slot.copy(building = building))
                    Log.d("fetchBuildingForSlots", "Building found for SlotID: ${slot.slotId} and TermID: $termId is $building")
                } else {
                    updatedSlots.add(slot)
                    Log.e("fetchBuildingForSlots", "Error fetching room data for SlotID: ${slot.slotId} and TermID: $termId: ${task.error}")
                }
                callbackCount++
                if (callbackCount == slots.size) {
                    completion(updatedSlots)
                }
            }
        }
    }

    private fun fetchAttendanceRecordsForSlots(slots: List<SlotInfo>, termId: ObjectId, completion: (List<SlotInfo>) -> Unit) {
        // Đảm bảo có studentId để truy vấn
        val studentId = this.studentId
        if (studentId == null) {
            Log.e("AttendanceError", "Student ID is null")
            return
        }

        val attendanceCollection = app.currentUser()?.getMongoClient("mongodb-atlas")?.getDatabase("finalProject")?.getCollection("Attendance")
        val slotsWithAttendance = mutableListOf<SlotInfo>()
        val specifiedDate = Date() // Use current date

        slots.forEach { slot ->
            val query = Document("\$and", listOf(
                Document("studentId", studentId),
                Document("courseId", Document("\$oid", slot.courseId)),
                Document("termId", termId),
                Document("slotId", Document("\$oid", slot.slotId))
            ))
            Log.d("AttendanceQuery", "Querying attendance for SlotID: ${slot.slotId}, CourseID: ${slot.courseId}, StudentID: $studentId, TermID: $termId")

            attendanceCollection?.findOne(query)?.getAsync { task ->
                if (task.isSuccess) {
                    val attendanceRecord = task.get()

                    Log.d("AttendanceResult", "Attendance record found - SlotID: ${slot.slotId}, CourseID: ${slot.courseId}, StudentID: $studentId, Status: ${attendanceRecord?.getString("status")}, Date: ${attendanceRecord?.getDate("date")}")

                    val attendanceDate = attendanceRecord?.getDate("date")
                    if (attendanceDate != null && isSameDay(attendanceDate, specifiedDate) && canAttend(slot.startTime)) {
                        slot.attendanceDate = SimpleDateFormat("dd/MM/yyyy").format(attendanceDate)
                        slot.attendanceStatus = attendanceRecord.getString("status")
                        slotsWithAttendance.add(slot)
                        Log.d("AttendanceUpdate", "Slot ${slot.slotId} is eligible for attendance today.")
                    }
                } else {
                    Log.e("AttendanceError", "Error fetching attendance record: ${task.error}")
                }

                if (slotsWithAttendance.size == slots.size || slots.indexOf(slot) == slots.lastIndex) {
                    Log.d("AttendanceCompletion", "Completed attendance check for all slots.")
                    completion(slotsWithAttendance)
                }
            }
        }
    }

    fun isSameDay(date1: Date?, date2: Date?): Boolean {
        if (date1 == null || date2 == null) return false

        val fmt = SimpleDateFormat("yyyyMMdd")
        return fmt.format(date1) == fmt.format(date2)
    }

    fun canAttend(startTime: String): Boolean {
        // Format and time zone
        val currentZone = TimeZone.getTimeZone("GMT+7")
        val format = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
            timeZone = currentZone
        }

        // get current day
        val specifiedDate = Date()
        val now = Calendar.getInstance(currentZone).apply {
            time = specifiedDate
        }

        // Parse the starting hour from the startTime string
        val startTimeParsed = format.parse(startTime) ?: return false
        val calendarStartTime = Calendar.getInstance(currentZone).apply {
            time = startTimeParsed
            set(Calendar.YEAR, now.get(Calendar.YEAR))
            set(Calendar.MONTH, now.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, now.get(Calendar.DAY_OF_MONTH))
        }

        // set the time for attendance
        val calendarStart = calendarStartTime.clone() as Calendar
        calendarStart.add(Calendar.MINUTE, -30) // open early 30 minute

        val calendarEnd = calendarStartTime.clone() as Calendar
        calendarEnd.add(Calendar.MINUTE, 30) // close after 30 minute

        // Check if the current time is within the allowed time range
        val canAttend = now.after(calendarStart) && now.before(calendarEnd)
        Log.d("canAttend", "Current time is within the attendance window: $canAttend (Start: ${format.format(calendarStart.time)}, End: ${format.format(calendarEnd.time)})")

        return canAttend
    }

    fun attendSlot(slotInfo: SlotInfo) {
        if (ContextCompat.checkSelfPermission(this, ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(ACCESS_FINE_LOCATION), REQUEST_LOCATION_PERMISSION)
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            location?.let {
                confirmAttendance(slotInfo, it)
            }
        }.addOnFailureListener {
            Toast.makeText(this, "Failed to get location.", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmAttendance(slotInfo: SlotInfo, location: Location) {
        val distanceInMeters = FloatArray(2)
        val schoolLatitude = 10.801074
        val schoolLongitude = 106.660633

        Location.distanceBetween(location.latitude, location.longitude, schoolLatitude, schoolLongitude, distanceInMeters)

        if (distanceInMeters[0] <= ALLOWED_DISTANCE_METERS) {
            // Hiển thị AlertDialog để xác nhận
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Confirm attendance")
            builder.setMessage("Are you sure you are in the attendance area?")
            builder.setPositiveButton("Attend") { dialog, which ->
                updateAttendanceRecord(slotInfo, "present")
                showResultDialog("Attendance has been successfully registered!")
            }
            builder.setNegativeButton("Cancel", { dialog, which -> dialog.dismiss() })
            builder.show()
        } else {
            showResultDialog("You are not in the allowed area.")
        }
    }

    private fun showResultDialog(message: String) {
        AlertDialog.Builder(this).apply {
            setTitle("Attendance results")
            setMessage(message)
            setPositiveButton("Ok") { dialog, which -> dialog.dismiss() }
            show()
        }
    }

    private fun updateAttendanceRecord(slotInfo: SlotInfo, status: String) {
        val attendanceCollection = app.currentUser()?.getMongoClient("mongodb-atlas")?.getDatabase("finalProject")?.getCollection("Attendance")
        val blockChainsCollection = app.currentUser()?.getMongoClient("mongodb-atlas")?.getDatabase("finalProject")?.getCollection("BlockChains")

        // Update the attendance status in the `Attendance` collection
        val query = Document("slotId", ObjectId(slotInfo.slotId)).append("studentId", ObjectId(studentId.toString()))
        val update = Document("\$set", Document("status", status))

        attendanceCollection?.updateOne(query, update)?.getAsync { result ->
            if (result.isSuccess) {
                Log.d("updateAttendance", "Attendance status updated successfully.")
                /*Toast.makeText(this, "Attendance marked as $status.", Toast.LENGTH_SHORT).show()*/

                /*if (status == "present") {
                    // Only increment LDTs if the status is "present"
                    val pointAdjustment = Document("\$inc", Document("LDTs", 0.1))
                    blockChainsCollection?.updateOne(Document("studentId", ObjectId(studentId.toString())), pointAdjustment)?.getAsync { task ->
                        if (task.isSuccess) {
                            Log.d("updateBlockchainPoints", "Blockchain points updated successfully for attending.")
                        } else {
                            Log.e("updateBlockchainPoints", "Failed to update points: ${task.error}")
                        }
                    }
                }*/

                // Fetch new blockchain data to ensure UI is updated
                fetchBlockchainData(studentId!!)

                // Update attendance status in slotsData and UI
                updateLocalSlotData(slotInfo.slotId, status)
                sendSlotsDataToInterfaceFragment(slotsData)
            } else {
                Log.e("updateAttendance", "Failed to update attendance status: ${result.error}")
                Toast.makeText(this, "Failed to mark attendance.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateLocalSlotData(slotId: String, status: String) {
        slotsData?.find { it.slotId == slotId }?.attendanceStatus = status
    }

    companion object {
        private const val REQUEST_LOCATION_PERMISSION = 1
        private const val ALLOWED_DISTANCE_METERS = 100f // Phạm vi cho phép là 100 mét
    }

    private fun sendSlotsDataToInterfaceFragment(slots: List<SlotInfo>? = null) {
        val dataToPass = slots ?: slotsData
        if (dataToPass != null) {
            Log.d("sendSlotsDataToInterfaceFragment", "Update Slots data in InterfaceFragment, quantity: ${dataToPass.size}")
            val interfaceFragment = supportFragmentManager.findFragmentById(R.id.frame_layout) as? InterfaceFragment
            if (interfaceFragment != null) {
                interfaceFragment.refreshSlotsData(dataToPass)
            } else {
                val newFragment = InterfaceFragment().apply {
                    arguments = Bundle().apply {
                        putSerializable("slotsData", ArrayList(dataToPass))
                    }
                }
                replaceFragment(newFragment)
            }
        } else {
            Log.e("sendSlotsDataToInterfaceFragment", "There is no Slots data to update InterfaceFragment.")
        }
    }

    private fun replaceFragment(fragment: Fragment) {
        val args = Bundle().apply {
            when (fragment) {
                is InterfaceFragment -> {
                    if (slotsData != null) {
                        putSerializable("slotsData", ArrayList(slotsData))
                    }
                    putString("addressWallet", addressWallet ?: "No address")  // Pass addressWallet to InterfaceFragment
                }
                is InforFragment -> {
                    if (coursesInfo != null) {
                        putString("name", studentName ?: "N/A")
                        putString("email", studentEmail ?: "N/A")
                        putString("department", departmentName ?: "N/A")
                        putSerializable("courses", ArrayList(coursesInfo))
                        putDouble("PP", blockchainPP ?: 0.0)  // Add PP blockchain information
                        putDouble("LDTs", blockchainLDTs ?: 0.0)  // Add LDTs blockchain information
                    }
                }
            }
        }
        fragment.arguments = args

        // Perform Fragment replacement on UI
        supportFragmentManager.beginTransaction().apply {
            replace(R.id.frame_layout, fragment, fragment.javaClass.simpleName)
            commit()
        }
    }
}

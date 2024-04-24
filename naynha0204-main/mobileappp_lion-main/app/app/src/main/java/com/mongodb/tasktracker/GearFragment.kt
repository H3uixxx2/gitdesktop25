package com.mongodb.tasktracker

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment

class GearFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_gear, container, false)

        val logoutButton = view.findViewById<Button>(R.id.LogoutButton)
        logoutButton.setOnClickListener {
            logout()
        }

        return view
    }

    private fun logout() {
        val mainActivity = activity as? HomeActivity
        mainActivity?.app?.currentUser()?.logOutAsync { result ->
            if (result.isSuccess) {
                activity?.finish()  // Đóng Activity sau khi đăng xuất thành công
            } else {
                result.error.printStackTrace()  // In lỗi nếu đăng xuất không thành công
            }
        }
    }
}


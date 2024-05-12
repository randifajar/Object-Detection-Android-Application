package com.detection.tomato;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.firebase.FirebaseApp;
import com.google.firebase.storage.FirebaseStorage;

import java.util.ArrayList;
import java.util.Objects;

public class RetrieveDatabase extends AppCompatActivity implements SwipeRefreshLayout.OnRefreshListener {

    SwipeRefreshLayout swipeRefreshLayout;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_retrieve_database);

        FirebaseApp.initializeApp(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        Objects.requireNonNull(getSupportActionBar()).setTitle("Hasil Deteksi");

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setHomeAsUpIndicator(R.drawable.round_arrow_back);

        swipeRefreshLayout = findViewById(R.id.swipe);
        swipeRefreshLayout.setOnRefreshListener(this);

        getImageData();
    }

    public void getImageData() {
        RecyclerView recyclerView = findViewById(R.id.recycler);
        FirebaseStorage.getInstance().getReference().child("detection_result").listAll().addOnSuccessListener(listResult -> {
            ArrayList<Image> arrayList = new ArrayList<>();
            MyAdapter adapter = new MyAdapter(RetrieveDatabase.this, arrayList);
            adapter.setOnItemClickListener(image -> {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(image.getURL()));
                intent.setDataAndType(Uri.parse(image.getURL()), "image/*");
                startActivity(intent);
            });
            recyclerView.setAdapter(adapter);
            listResult.getItems().forEach(storageReference -> {
                Image image = new Image();
                image.setName(storageReference.getName());
                storageReference.getDownloadUrl().addOnCompleteListener(task -> {
                    String url = "https://" + task.getResult().getEncodedAuthority() + task.getResult().getEncodedPath() + "?alt=media&token=" + task.getResult().getQueryParameters("token").get(0);
                    image.setURL(url);
                    arrayList.add(image);
                    adapter.notifyDataSetChanged();
                });

            });
        }).addOnFailureListener(e -> Toast.makeText(RetrieveDatabase.this, "Failed to retrieve images", Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onRefresh() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            getImageData();
            swipeRefreshLayout.setRefreshing(false);
        }, 3000);
    }
}